package com.winlator.xenvironment;

import android.content.Context;

import androidx.appcompat.app.AppCompatActivity;

import com.winlator.MainActivity;
import com.winlator.R;
import com.winlator.SettingsFragment;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.core.AppUtils;
import com.winlator.core.DownloadProgressDialog;
import com.winlator.core.FileUtils;
import com.winlator.core.PreloaderDialog;
import com.winlator.core.StagedInstaller;
import com.winlator.core.StorageChecker;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.WineInfo;
import com.winlator.contentdialog.ContentDialog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public abstract class RootFSInstaller {
    public static final byte LATEST_VERSION = 23; // TODO increment it on rootfs update
    public static final byte UPDATE_WINEPREFIX_VERSION = 16; // set it if main wine version change
    public static final String FILENAME = "rootfs.tzst";

    private static void resetContainerRFSVersions(Context context) {
        ContainerManager manager = new ContainerManager(context);
        for (Container container : manager.getContainers()) {
            String rfsVersion = container.getExtra("rfsVersion");
            String wineVersion = container.getWineVersion();
            if (!rfsVersion.isEmpty() && WineInfo.isMainWineVersion(wineVersion) && Short.parseShort(rfsVersion) <= UPDATE_WINEPREFIX_VERSION) {
                container.putExtra("wineprefixNeedsUpdate", "t");
            }

            container.putExtra("rfsVersion", null);
            container.saveData();
        }
    }

    public static void install(final MainActivity activity) {
        if (!AppUtils.isUiThread()) {
            // UI setup (window flags, progress dialog) must run on the main
            // thread; the extraction itself stays on the worker executor below
            activity.runOnUiThread(() -> install(activity));
            return;
        }

        AppUtils.keepScreenOn(activity);
        final RootFS rootFS = RootFS.find(activity);
        final File rootDir = rootFS.getRootDir();

        SettingsFragment.resetPreferenceVersions(activity);

        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        dialog.show(R.string.installing_system_files);
        Executors.newSingleThreadExecutor().execute(() -> {
            // recover from any interrupted install/update before touching anything
            rootFS.recoverFromInterruptedLaunch();
            StagedInstaller.deleteRecursive(rootFS.getStagingDir());

            final long contentLength = TarCompressorUtils.getContentLength(TarCompressorUtils.Type.ZSTD, activity, FILENAME, rootFS.getStagingDir());

            // staged install keeps the live rootfs plus a full staging copy
            StorageChecker.Result storageResult = StorageChecker.checkRootfsInstall(rootDir, contentLength);
            if (!storageResult.sufficient) {
                dialog.closeOnUiThread();
                showNotEnoughStorageDialog(activity, storageResult);
                return;
            }

            final File stagingDir = rootFS.getStagingDir();
            AtomicLong totalSizeRef = new AtomicLong();

            boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, activity, FILENAME, stagingDir, (file, size) -> {
                if (size > 0) {
                    long totalSize = totalSizeRef.addAndGet(size);
                    final int progress = (int)(((float)totalSize / contentLength) * 100);
                    activity.runOnUiThread(() -> dialog.setProgress(progress));
                }
                return file;
            });

            // validate the staged rootfs (required paths + expected size) and
            // switch it into place (with rollback)
            success = success && StagedInstaller.validate(stagingDir, contentLength).valid
                && StagedInstaller.commit(rootDir, stagingDir, rootFS.getBackupDir());

            if (success) {
                rootFS.createRFSVersionFile(LATEST_VERSION);
                resetContainerRFSVersions(activity);
            }
            else AppUtils.showToast(activity, R.string.unable_to_install_system_files);

            dialog.closeOnUiThread();
        });
    }

    public static void installIfNeeded(final MainActivity activity) {
        Executors.newSingleThreadExecutor().execute(() -> {
            RootFS rootFS = RootFS.find(activity);
            if (rootFS.recoverFromInterruptedLaunch()) {
                // a previous launch of the updated system failed: stay on the
                // restored system and let the user trigger the update manually
                AppUtils.showToast(activity, R.string.restored_previous_system_files);
                return;
            }
            if (!rootFS.isValid() || rootFS.getVersion() < LATEST_VERSION) install(activity);
        });
    }

    private static void showNotEnoughStorageDialog(final MainActivity activity, final StorageChecker.Result result) {
        activity.runOnUiThread(() -> {
            ContentDialog dialog = new ContentDialog(activity);
            dialog.setTitle(R.string.not_enough_storage);
            dialog.setMessage(activity.getString(R.string.free_storage_space_hint, Math.max(1, result.missingBytes() / (1024 * 1024))));
            dialog.findViewById(R.id.BTCancel).setVisibility(android.view.View.GONE);
            dialog.show();
        });
    }

    public static void generateCompactContainerPattern(final AppCompatActivity activity) {
        AppUtils.keepScreenOn(activity);
        PreloaderDialog preloaderDialog = new PreloaderDialog(activity);
        preloaderDialog.show(R.string.loading);
        Executors.newSingleThreadExecutor().execute(() -> {
            File[] srcFiles, dstFiles;
            File rootDir = RootFS.find(activity).getRootDir();
            File wineSystem32Dir = new File(rootDir, "/opt/wine/lib/wine/x86_64-windows");
            File wineSysWoW64Dir = new File(rootDir, "/opt/wine/lib/wine/i386-windows");

            File containerPatternDir = new File(activity.getCacheDir(), "container_pattern");
            FileUtils.delete(containerPatternDir);
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, activity, "container_pattern.tzst", containerPatternDir);

            File containerSystem32Dir = new File(containerPatternDir, ".wine/drive_c/windows/system32");
            File containerSysWoW64Dir = new File(containerPatternDir, ".wine/drive_c/windows/syswow64");

            dstFiles = containerSystem32Dir.listFiles();
            srcFiles = wineSystem32Dir.listFiles();

            ArrayList<String> system32Files = new ArrayList<>();
            ArrayList<String> syswow64Files = new ArrayList<>();

            for (File dstFile : dstFiles) {
                for (File srcFile : srcFiles) {
                    if (dstFile.getName().equals(srcFile.getName())) {
                        if (FileUtils.contentEquals(srcFile, dstFile)) system32Files.add(srcFile.getName());
                        break;
                    }
                }
            }

            dstFiles = containerSysWoW64Dir.listFiles();
            srcFiles = wineSysWoW64Dir.listFiles();

            for (File dstFile : dstFiles) {
                for (File srcFile : srcFiles) {
                    if (dstFile.getName().equals(srcFile.getName())) {
                        if (FileUtils.contentEquals(srcFile, dstFile)) syswow64Files.add(srcFile.getName());
                        break;
                    }
                }
            }

            try {
                JSONObject data = new JSONObject();

                JSONArray system32JSONArray = new JSONArray();
                for (String name : system32Files) {
                    FileUtils.delete(new File(containerSystem32Dir, name));
                    system32JSONArray.put(name);
                }
                data.put("system32", system32JSONArray);

                JSONArray syswow64JSONArray = new JSONArray();
                for (String name : syswow64Files) {
                    FileUtils.delete(new File(containerSysWoW64Dir, name));
                    syswow64JSONArray.put(name);
                }
                data.put("syswow64", syswow64JSONArray);

                FileUtils.writeString(new File(activity.getCacheDir(), "common_dlls.json"), data.toString());

                File outputFile = new File(activity.getCacheDir(), "container_pattern.tzst");
                FileUtils.delete(outputFile);
                TarCompressorUtils.compress(TarCompressorUtils.Type.ZSTD, new File(containerPatternDir, ".wine"), outputFile, 22);
            }
            catch (JSONException | IOException e) {}
            finally {
                FileUtils.delete(containerPatternDir);
                preloaderDialog.closeOnUiThread();
            }
        });
    }
}
