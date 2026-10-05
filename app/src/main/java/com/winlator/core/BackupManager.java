package com.winlator.core;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.InputControlsManager;
import com.winlator.xenvironment.RootFS;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Container/settings backup (export &amp; restore) support.
 *
 * An exported backup is a tar.zst archive with the {@code .wcb} extension
 * containing:
 * <pre>
 * manifest.json   - metadata + SHA-256 checksum of every entry
 * container/      - the container directory (home/xuser-&lt;id&gt;)
 * shortcuts/      - shortcut files belonging to the container
 * </pre>
 *
 * Restore never overwrites existing data: when the preferred target already
 * exists a free {@code -restored} name is picked instead
 * ({@link #resolveConflictTarget(File)}).
 *
 * Pure Java (no Android dependencies) so the policy can be validated by plain
 * JVM unit tests (see {@code BackupManagerTest}); the Android-facing
 * export/restore entry points live at the bottom of this class.
 */
public abstract class BackupManager {
    public static final String EXTENSION = ".wcb";
    public static final String MANIFEST_FILENAME = IntegrityVerifier.MANIFEST_FILENAME;
    public static final String CONTAINER_DIR = "container";
    public static final String SHORTCUTS_DIR = "shortcuts";
    public static final String PROFILES_DIR = "profiles";
    public static final String TYPE_CONTAINER = "container";
    public static final String TYPE_GLOBAL = "global";
    /** Upper bound for the expanded size of a restored backup (untrusted input). */
    public static final long MAX_EXTRACTED_BYTES = 8L * 1024 * 1024 * 1024;

    /**
     * Picks a target directory that never overwrites existing data.
     * If {@code preferred} is taken, {@code <name>-restored} is used, then
     * {@code <name>-restored-2}, {@code <name>-restored-3}, ...
     */
    public static File resolveConflictTarget(File preferred) {
        File target = preferred;
        int counter = 0;
        while (target.exists()) {
            counter++;
            String suffix = "-restored" + (counter > 1 ? "-" + counter : "");
            target = new File(preferred.getParentFile(), preferred.getName() + suffix);
        }
        return target;
    }

    /**
     * Verifies an extracted backup directory against its manifest.
     * Returns the list of problems found (empty list = backup is intact).
     */
    public static List<String> verify(File extractedDir) {
        List<String> errors = new ArrayList<>();
        File manifestFile = extractedDir != null ? new File(extractedDir, MANIFEST_FILENAME) : null;

        if (manifestFile == null || !manifestFile.isFile()) {
            errors.add(MANIFEST_FILENAME + " is missing");
            return errors;
        }

        try {
            String content = new String(Files.readAllBytes(manifestFile.toPath()), StandardCharsets.UTF_8);
            JSONObject manifest = new JSONObject(content);
            return IntegrityVerifier.verifyManifest(manifest, extractedDir);
        }
        catch (IOException | JSONException e) {
            errors.add(MANIFEST_FILENAME + " is unreadable (" + e.getMessage() + ")");
            return errors;
        }
    }

    /** Builds the manifest for an extracted backup directory (all entries except the manifest itself). */
    public static JSONObject buildManifest(File extractedDir, JSONObject meta) throws IOException {
        return IntegrityVerifier.buildManifest(extractedDir, meta);
    }

    // ---------------- Android-facing export / restore ----------------

    /** Exports a container (including its shortcuts and .wine prefix) plus the input profiles. */
    public static File exportContainer(Context context, Container container) throws IOException {
        File stagingDir = new File(context.getCacheDir(), "wcb_export");
        StagedInstaller.deleteRecursive(stagingDir);
        stagingDir.mkdirs();

        try {
            File containerStaging = new File(stagingDir, CONTAINER_DIR);
            containerStaging.mkdirs();
            if (!FileUtils.copy(container.getRootDir(), containerStaging, (file) -> FileUtils.chmod(file, 0771))) {
                throw new IOException("unable to copy the container directory");
            }
            copyProfiles(context, stagingDir);

            JSONObject meta = new JSONObject();
            try {
                meta.put("type", TYPE_CONTAINER);
                meta.put("containerName", container.getName());
                meta.put("wineVersion", container.getWineVersion());
                meta.put("graphicsDriver", container.getGraphicsDriver());
                meta.put("graphicsDriverConfig", container.getGraphicsDriverConfig());
            }
            catch (JSONException e) {}

            File destination = new File(getBackupsDir(), sanitizeFileName(container.getName()) + EXTENSION);
            return writeArchive(context, stagingDir, destination, meta);
        }
        finally {
            StagedInstaller.deleteRecursive(stagingDir);
        }
    }

    /** Exports the input profiles and the shortcuts of every container. */
    public static File exportGlobal(Context context) throws IOException {
        File stagingDir = new File(context.getCacheDir(), "wcb_export");
        StagedInstaller.deleteRecursive(stagingDir);
        stagingDir.mkdirs();

        try {
            copyProfiles(context, stagingDir);

            File shortcutsStaging = new File(stagingDir, SHORTCUTS_DIR);
            shortcutsStaging.mkdirs();
            ContainerManager manager = new ContainerManager(context);
            for (Container container : manager.getContainers()) {
                File desktopDir = new File(container.getUserDir(), "Desktop");
                if (desktopDir.isDirectory()) {
                    File target = new File(shortcutsStaging, sanitizeFileName(container.getName()));
                    target.mkdirs();
                    if (!FileUtils.copy(desktopDir, target, (file) -> FileUtils.chmod(file, 0771))) {
                        throw new IOException("unable to copy the shortcuts of " + container.getName());
                    }
                }
            }

            JSONObject meta = new JSONObject();
            try {
                meta.put("type", TYPE_GLOBAL);
            }
            catch (JSONException e) {}

            File destination = new File(getBackupsDir(), "winlator-backup" + EXTENSION);
            return writeArchive(context, stagingDir, destination, meta);
        }
        finally {
            StagedInstaller.deleteRecursive(stagingDir);
        }
    }

    private static void copyProfiles(Context context, File stagingDir) throws IOException {
        File profilesDir = InputControlsManager.getProfilesDir(context);
        if (profilesDir.isDirectory()) {
            File profilesStaging = new File(stagingDir, PROFILES_DIR);
            profilesStaging.mkdirs();
            if (!FileUtils.copy(profilesDir, profilesStaging, (file) -> FileUtils.chmod(file, 0771))) {
                throw new IOException("unable to copy the input profiles");
            }
        }
    }

    private static File writeArchive(Context context, File stagingDir, File destination, JSONObject meta) throws IOException {
        try {
            meta.put("appVersion", getAppVersion(context));
            meta.put("rfsVersion", RootFS.find(context).getVersion());
        }
        catch (JSONException e) {}

        JSONObject manifest = buildManifest(stagingDir, meta);
        FileUtils.writeString(new File(stagingDir, MANIFEST_FILENAME), manifest.toString());

        destination = resolveConflictTarget(destination);
        File parent = destination.getParentFile();
        if (parent != null) parent.mkdirs();

        try {
            TarCompressorUtils.compress(TarCompressorUtils.Type.ZSTD, stagingDir.listFiles(), destination, 3);
            if (!destination.isFile()) throw new IOException("unable to write the backup archive");
            // an incomplete archive must never be reported as a successful backup
            // (the migration flow relies on this to decide whether it may proceed)
            verifyArchive(TarCompressorUtils.Type.ZSTD, destination, new File(context.getCacheDir(), "wcb_verify"));
        }
        catch (IOException e) {
            StagedInstaller.deleteRecursive(destination);
            throw e;
        }

        MediaScannerConnection.scanFile(context, new String[]{destination.getAbsolutePath()}, null, null);
        return destination;
    }

    /**
     * Extracts a finished backup archive and verifies its manifest, so
     * truncated or silently incomplete archives are detected before the export
     * is reported as successful. Throws {@link IOException} on any problem.
     */
    public static void verifyArchive(TarCompressorUtils.Type type, File archive, File workDir) throws IOException {
        try {
            StagedInstaller.deleteRecursive(workDir);
            workDir.mkdirs();
            if (!TarCompressorUtils.extractSafe(type, archive, workDir, MAX_EXTRACTED_BYTES)) {
                throw new IOException("the backup archive is unreadable");
            }
            List<String> errors = verify(workDir);
            if (!errors.isEmpty()) throw new IOException("the backup archive failed verification (" + errors.get(0) + ")");
        }
        finally {
            StagedInstaller.deleteRecursive(workDir);
        }
    }

    private static File getBackupsDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Winlator/backups");
    }

    private static String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String getAppVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        }
        catch (Exception e) {
            return "unknown";
        }
    }
    /** Result of a restore run. Existing data is never overwritten. */
    public static class RestoreResult {
        public final ArrayList<String> errors = new ArrayList<>();
        public final ArrayList<String> conflicts = new ArrayList<>();
        public Container container;

        public boolean isSuccess() {
            return errors.isEmpty();
        }
    }

    /**
     * Restores a {@code .wcb} backup (container and/or global). Checksums are
     * verified first and existing data is never overwritten: containers get a
     * fresh id, conflicting shortcut files are renamed and same-name profiles
     * are reported as conflicts.
     */
    public static RestoreResult restore(Context context, Uri source) {
        RestoreResult result = new RestoreResult();
        File tmpDir = new File(context.getCacheDir(), "wcb_restore");
        StagedInstaller.deleteRecursive(tmpDir);
        tmpDir.mkdirs();

        try {
            File archiveFile = new File(tmpDir, "backup" + EXTENSION);
            try (InputStream inStream = context.getContentResolver().openInputStream(source);
                 OutputStream outStream = new FileOutputStream(archiveFile)) {
                if (inStream == null) throw new IOException("unable to read the source file");

                byte[] buffer = new byte[8192];
                int length;
                while ((length = inStream.read(buffer)) > 0) {
                    outStream.write(buffer, 0, length);
                }
            }

            File extractedDir = new File(tmpDir, "extracted");
            extractedDir.mkdirs();
            // untrusted input: strict path containment and size cap during extraction
            if (!TarCompressorUtils.extractSafe(TarCompressorUtils.Type.ZSTD, archiveFile, extractedDir, MAX_EXTRACTED_BYTES)) {
                result.errors.add("unable to extract the backup archive");
                return result;
            }

            result.errors.addAll(verify(extractedDir));
            if (!result.errors.isEmpty()) return result;

            restoreContainer(context, extractedDir, result);
            restoreProfiles(context, extractedDir, result);
            restoreShortcuts(context, extractedDir, result);
        }
        catch (IOException e) {
            result.errors.add("unable to read the backup archive (" + e.getMessage() + ")");
        }
        finally {
            StagedInstaller.deleteRecursive(tmpDir);
        }
        return result;
    }

    private static void restoreContainer(Context context, File extractedDir, RestoreResult result) {
        File containerDir = new File(extractedDir, CONTAINER_DIR);
        if (!containerDir.isDirectory()) return;

        ContainerManager manager = new ContainerManager(context);
        Container container = manager.restoreContainer(containerDir);
        if (container != null) result.container = container;
        else result.errors.add("unable to restore the container");
    }

    private static void restoreProfiles(Context context, File extractedDir, RestoreResult result) {
        File profilesDir = new File(extractedDir, PROFILES_DIR);
        File[] files = profilesDir.isDirectory() ? profilesDir.listFiles() : null;
        if (files == null) return;

        InputControlsManager inputControlsManager = new InputControlsManager(context);
        ArrayList<ControlsProfile> existingProfiles = inputControlsManager.getProfiles(false);

        for (File file : files) {
            if (!file.isFile()) continue;

            try {
                JSONObject data = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                String name = data.optString("name", file.getName());

                for (ControlsProfile profile : existingProfiles) {
                    if (profile.getName().equals(name)) {
                        result.conflicts.add(name);
                        break;
                    }
                }

                if (inputControlsManager.importProfile(data) == null) {
                    result.errors.add(name + ": unable to import the profile");
                }
            }
            catch (IOException | JSONException e) {
                result.errors.add(file.getName() + ": unreadable profile (" + e.getMessage() + ")");
            }
        }
    }

    private static void restoreShortcuts(Context context, File extractedDir, RestoreResult result) {
        File shortcutsDir = new File(extractedDir, SHORTCUTS_DIR);
        File[] folders = shortcutsDir.isDirectory() ? shortcutsDir.listFiles() : null;
        if (folders == null) return;

        ContainerManager manager = new ContainerManager(context);
        for (File folder : folders) {
            if (!folder.isDirectory()) continue;

            Container target = null;
            for (Container container : manager.getContainers()) {
                if (sanitizeFileName(container.getName()).equals(folder.getName())) {
                    target = container;
                    break;
                }
            }

            if (target == null) {
                result.conflicts.add(folder.getName() + ": container not found, shortcuts skipped");
                continue;
            }

            File desktopDir = new File(target.getUserDir(), "Desktop");
            desktopDir.mkdirs();
            File[] files = folder.listFiles();
            if (files == null) continue;

            for (File file : files) {
                if (!file.isFile()) continue;

                File destination = new File(desktopDir, file.getName());
                if (destination.exists()) {
                    destination = resolveConflictTarget(destination);
                    result.conflicts.add(file.getName());
                }

                if (!FileUtils.copy(file, destination)) {
                    result.errors.add(file.getName() + ": unable to restore the shortcut");
                }
            }
        }
    }
}
