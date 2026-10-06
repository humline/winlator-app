package com.winlator.core;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.provider.OpenableColumns;

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
    /** Stable container reference stored inside each shortcut group of a global backup. */
    public static final String CONTAINER_REF_FILENAME = "container.json";
    public static final String TYPE_CONTAINER = "container";
    public static final String TYPE_GLOBAL = "global";
    /** Upper bound for the compressed size of a restored backup (untrusted input). */
    public static final long MAX_ARCHIVE_BYTES = 4L * 1024 * 1024 * 1024;
    /** Upper bound for the expanded size of a restored backup (untrusted input). */
    public static final long MAX_EXTRACTED_BYTES = 8L * 1024 * 1024 * 1024;

    public interface ProgressListener {
        void onProgress(int percent);
    }

    private static long getContentLength(Context context, Uri source) {
        if (source == null) return -1;
        try (Cursor cursor = context.getContentResolver().query(source, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int columnIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                return columnIndex >= 0 ? cursor.getLong(columnIndex) : -1;
            }
        }
        catch (Exception e) {}
        return -1;
    }

    private static class ProgressReporter {
        private final ProgressListener listener;
        private int lastPercent = -1;

        ProgressReporter(ProgressListener listener) {
            this.listener = listener;
        }

        void report(int percent) {
            if (listener != null && percent != lastPercent) {
                lastPercent = percent;
                listener.onProgress(percent);
            }
        }
    }

    /**
     * Free cache space required before extracting a restored backup: roughly
     * twice the compressed size (typical expansion), with headroom for tiny
     * archives, bounded by {@link #MAX_EXTRACTED_BYTES}.
     */
    public static long restorePreflightBytes(long archiveSize) {
        return Math.min(Math.max(archiveSize * 2, StorageChecker.HEADROOM_BYTES), MAX_EXTRACTED_BYTES);
    }

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
     * Name of a shortcut group inside a global backup. Keyed by the stable
     * container id so same-named containers can never merge into one group.
     */
    public static String shortcutsFolderName(int containerId) {
        return "c" + containerId;
    }

    /** Identity of the container a shortcut group belongs to. */
    public static class ContainerRef {
        public final int id;
        public final String name;

        public ContainerRef(int id, String name) {
            this.id = id;
            this.name = name != null ? name : "";
        }
    }

    /** Serializes a {@link ContainerRef} for {@link #CONTAINER_REF_FILENAME}. */
    public static String containerRefJson(int id, String name) {
        try {
            JSONObject data = new JSONObject();
            data.put("id", id);
            data.put("name", name != null ? name : "");
            return data.toString();
        }
        catch (JSONException e) {
            return null;
        }
    }

    /** Parses the {@link #CONTAINER_REF_FILENAME} of a shortcut group ({@code null} when absent or malformed). */
    public static ContainerRef parseContainerRef(String json) {
        if (json == null) return null;
        try {
            JSONObject data = new JSONObject(json);
            return new ContainerRef(data.optInt("id", -1), data.optString("name", ""));
        }
        catch (JSONException e) {
            return null;
        }
    }

    /**
     * Resolves the container of a shortcut group explicitly: exact id+name
     * match first, then a unique name match (restored containers get fresh
     * ids), then the id match (same device, renamed container). Returns the
     * index into {@code candidates} or {@code -1} when the reference is
     * missing or only ambiguous candidates exist.
     */
    public static int resolveContainerRef(ContainerRef ref, List<ContainerRef> candidates) {
        if (ref == null || candidates == null) return -1;

        int idMatch = -1;
        int nameMatch = -1;
        int nameMatches = 0;

        for (int i = 0; i < candidates.size(); i++) {
            ContainerRef candidate = candidates.get(i);
            if (candidate.id == ref.id) idMatch = i;
            if (candidate.name.equals(ref.name)) {
                nameMatch = i;
                nameMatches++;
            }
        }

        if (idMatch != -1 && candidates.get(idMatch).name.equals(ref.name)) return idMatch;
        if (nameMatches == 1) return nameMatch;
        return idMatch;
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
        return exportContainer(context, container, null);
    }

    public static File exportContainer(Context context, Container container, ProgressListener listener) throws IOException {
        File stagingDir = new File(context.getCacheDir(), "wcb_export");
        StagedInstaller.deleteRecursive(stagingDir);
        stagingDir.mkdirs();

        try {
            File containerStaging = new File(stagingDir, CONTAINER_DIR);
            containerStaging.mkdirs();
            File profilesDir = InputControlsManager.getProfilesDir(context);
            ExportProgress progress = new ExportProgress(listener, container.getRootDir(), profilesDir);
            if (!progress.copy(container.getRootDir(), containerStaging, (file) -> FileUtils.chmod(file, 0771))) {
                throw new IOException("unable to copy the container directory");
            }
            copyProfiles(context, stagingDir, progress);

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
            return writeArchive(context, stagingDir, destination, meta, listener);
        }
        finally {
            StagedInstaller.deleteRecursive(stagingDir);
        }
    }

    /** Exports the input profiles and the shortcuts of every container. */
    public static File exportGlobal(Context context) throws IOException {
        return exportGlobal(context, null);
    }

    public static File exportGlobal(Context context, ProgressListener listener) throws IOException {
        File stagingDir = new File(context.getCacheDir(), "wcb_export");
        StagedInstaller.deleteRecursive(stagingDir);
        stagingDir.mkdirs();

        try {
            ContainerManager manager = new ContainerManager(context);
            List<Container> containers = manager.getContainers();
            List<File> copySources = new ArrayList<>();
            File profilesDir = InputControlsManager.getProfilesDir(context);
            copySources.add(profilesDir);
            for (Container container : containers) {
                File desktopDir = new File(container.getUserDir(), "Desktop");
                if (desktopDir.isDirectory()) copySources.add(desktopDir);
            }
            ExportProgress progress = new ExportProgress(listener, copySources.toArray(new File[0]));
            copyProfiles(context, stagingDir, progress);

            File shortcutsStaging = new File(stagingDir, SHORTCUTS_DIR);
            shortcutsStaging.mkdirs();
            for (Container container : containers) {
                File desktopDir = new File(container.getUserDir(), "Desktop");
                if (desktopDir.isDirectory()) {
                    // key by the stable container id so same-named containers
                    // can never merge into one shortcut group
                    File target = new File(shortcutsStaging, shortcutsFolderName(container.id));
                    target.mkdirs();
                    if (!progress.copy(desktopDir, target, (file) -> FileUtils.chmod(file, 0771))) {
                        throw new IOException("unable to copy the shortcuts of " + container.getName());
                    }

                    String refJson = containerRefJson(container.id, container.getName());
                    if (refJson == null || !FileUtils.writeString(new File(target, CONTAINER_REF_FILENAME), refJson)) {
                        throw new IOException("unable to record the container reference of " + container.getName());
                    }
                }
            }

            JSONObject meta = new JSONObject();
            try {
                meta.put("type", TYPE_GLOBAL);
            }
            catch (JSONException e) {}

            File destination = new File(getBackupsDir(), "winlator-backup" + EXTENSION);
            return writeArchive(context, stagingDir, destination, meta, listener);
        }
        finally {
            StagedInstaller.deleteRecursive(stagingDir);
        }
    }

    private static void copyProfiles(Context context, File stagingDir, ExportProgress progress) throws IOException {
        File profilesDir = InputControlsManager.getProfilesDir(context);
        if (profilesDir.isDirectory()) {
            File profilesStaging = new File(stagingDir, PROFILES_DIR);
            profilesStaging.mkdirs();
            if (!progress.copy(profilesDir, profilesStaging, (file) -> FileUtils.chmod(file, 0771))) {
                throw new IOException("unable to copy the input profiles");
            }
        }
    }

    private static class ExportProgress {
        private final ProgressListener listener;
        private final ProgressReporter reporter;
        private final long totalBytes;
        private long completedBytes;

        ExportProgress(ProgressListener listener, File... sources) {
            this.listener = listener;
            reporter = listener != null ? new ProgressReporter(listener) : null;
            long total = 0;
            for (File source : sources) total += FileUtils.getTotalFileSize(source);
            totalBytes = total;
        }

        boolean copy(File source, File destination, Callback<File> callback) {
            if (listener == null) return FileUtils.copy(source, destination, callback);

            long sourceBytes = FileUtils.getTotalFileSize(source);
            long base = completedBytes;
            boolean copied = FileUtils.copy(source, destination, callback, (processed, total) -> {
                int percent = totalBytes > 0 ? (int)Math.min(45, (base + processed) * 45 / totalBytes) : 45;
                reporter.report(percent);
            });
            if (copied) completedBytes += sourceBytes;
            return copied;
        }
    }

    private static File writeArchive(Context context, File stagingDir, File destination, JSONObject meta, ProgressListener listener) throws IOException {
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

        ProgressReporter reporter = listener != null ? new ProgressReporter(listener) : null;
        try {
            TarCompressorUtils.compress(TarCompressorUtils.Type.ZSTD, stagingDir.listFiles(), destination, 3, reporter == null ? null : (processed, total) -> {
                int percent = total > 0 ? 45 + (int)Math.min(54, processed * 54 / total) : 99;
                reporter.report(percent);
            });
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
        if (reporter != null) reporter.report(100);
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
     * are reported as conflicts and skipped.
     */
    public static RestoreResult restore(Context context, Uri source) {
        return restore(context, source, null);
    }

    public static RestoreResult restore(Context context, Uri source, ProgressListener listener) {
        RestoreResult result = new RestoreResult();
        ProgressReporter reporter = listener != null ? new ProgressReporter(listener) : null;
        File tmpDir = new File(context.getCacheDir(), "wcb_restore");
        StagedInstaller.deleteRecursive(tmpDir);
        tmpDir.mkdirs();

        try {
            File archiveFile = new File(tmpDir, "backup" + EXTENSION);
            long sourceSize = getContentLength(context, source);
            try (InputStream inStream = context.getContentResolver().openInputStream(source);
                 OutputStream outStream = new FileOutputStream(archiveFile)) {
                if (inStream == null) throw new IOException("unable to read the source file");
                // bounded copy: a huge or malicious file cannot fill the cache
                StreamUtils.copyCapped(inStream, outStream, MAX_ARCHIVE_BYTES, sourceSize, reporter == null || sourceSize <= 0 ? null : (processed, total) -> {
                    reporter.report((int)Math.min(9, processed * 10 / total));
                });
            }

            // preflight before extracting: the cache must be able to hold the
            // expanded content (enforced again during extraction via the cap)
            StorageChecker.Result storageResult = StorageChecker.check(tmpDir, restorePreflightBytes(archiveFile.length()));
            if (!storageResult.sufficient) {
                result.errors.add("not enough storage to restore the backup");
                return result;
            }

            File extractedDir = new File(tmpDir, "extracted");
            extractedDir.mkdirs();
            // untrusted input: strict path containment and size cap during extraction
            if (!TarCompressorUtils.extractSafe(TarCompressorUtils.Type.ZSTD, archiveFile, extractedDir, MAX_EXTRACTED_BYTES, reporter == null ? null : (processed, total) -> {
                int percent = total > 0 ? 10 + (int)Math.min(89, processed * 89 / total) : 99;
                reporter.report(percent);
            })) {
                result.errors.add("unable to extract the backup archive");
                return result;
            }

            result.errors.addAll(verify(extractedDir));
            if (!result.errors.isEmpty()) return result;

            restoreContainer(context, extractedDir, result);
            restoreProfiles(context, extractedDir, result);
            restoreShortcuts(context, extractedDir, result);
            if (reporter != null) reporter.report(100);
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

                boolean conflict = false;
                for (ControlsProfile profile : existingProfiles) {
                    if (profile.getName().equals(name)) {
                        result.conflicts.add(name);
                        conflict = true;
                        break;
                    }
                }
                // existing data is never modified: the same-name profile is kept
                // untouched and the imported copy is skipped, so a restart cannot
                // resurrect a duplicate
                if (conflict) continue;

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
        List<Container> containers = manager.getContainers();
        List<ContainerRef> candidates = new ArrayList<>();
        for (Container container : containers) candidates.add(new ContainerRef(container.id, container.getName()));

        for (File folder : folders) {
            if (!folder.isDirectory()) continue;

            Container target = resolveShortcutContainer(folder, containers, candidates);
            if (target == null) {
                result.conflicts.add(folder.getName() + ": container not found, shortcuts skipped");
                continue;
            }

            File desktopDir = new File(target.getUserDir(), "Desktop");
            desktopDir.mkdirs();
            File[] files = folder.listFiles();
            if (files == null) continue;

            for (File file : files) {
                if (!file.isFile() || file.getName().equals(CONTAINER_REF_FILENAME)) continue;

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

    /**
     * Resolves the container of a shortcut group via its stable
     * {@link #CONTAINER_REF_FILENAME}; legacy archives without the reference
     * are matched by a unique sanitized container name (never guessed).
     */
    private static Container resolveShortcutContainer(File folder, List<Container> containers, List<ContainerRef> candidates) {
        ContainerRef ref = parseContainerRef(readStringOrNull(new File(folder, CONTAINER_REF_FILENAME)));
        if (ref != null) {
            int index = resolveContainerRef(ref, candidates);
            return index != -1 ? containers.get(index) : null;
        }

        Container found = null;
        for (Container container : containers) {
            if (sanitizeFileName(container.getName()).equals(folder.getName())) {
                if (found != null) return null; // ambiguous name: never guess
                found = container;
            }
        }
        return found;
    }

    private static String readStringOrNull(File file) {
        try {
            return file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : null;
        }
        catch (IOException e) {
            return null;
        }
    }
}
