package com.winlator.core;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Swaps freshly extracted driver files into place while keeping the replaced
 * files as {@code .bak} backups until validation passes; rolls back on failure.
 *
 * Pure Java (no Android dependencies) so the switching logic can be validated
 * by plain JVM unit tests (see {@code StagedLibSwapTest}).
 */
public abstract class StagedLibSwap {
    public static final String BACKUP_SUFFIX = ".bak";

    /**
     * Moves every file below {@code stagingDir} to the same relative location
     * below {@code rootDir}. Existing files are renamed to {@code <name>.bak}
     * first so they can be restored. Returns the moved relative paths.
     */
    public static List<String> swap(File stagingDir, File rootDir) throws IOException {
        return swap(stagingDir, rootDir, new ArrayList<>());
    }

    /** Same as {@link #swap(File, File)} but fills a caller-owned list (usable for rollback after partial swaps). */
    public static List<String> swap(File stagingDir, File rootDir, List<String> moved) throws IOException {
        swapRecursive(stagingDir, stagingDir, rootDir, moved);
        return moved;
    }

    private static void swapRecursive(File dir, File stagingDir, File rootDir, List<String> moved) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                swapRecursive(file, stagingDir, rootDir, moved);
                continue;
            }

            String relativePath = relativePath(stagingDir, file);
            File target = new File(rootDir, relativePath);
            File parent = target.getParentFile();
            if (parent != null) parent.mkdirs();

            if (target.exists()) {
                File backup = new File(target.getPath() + BACKUP_SUFFIX);
                Files.deleteIfExists(backup.toPath());
                if (!target.renameTo(backup)) throw new IOException("unable to back up " + relativePath);
            }

            if (!file.renameTo(target)) throw new IOException("unable to install " + relativePath);
            moved.add(relativePath);
        }
    }

    /**
     * Validates that all swapped files are in place and that Vulkan ICD
     * manifests are valid. Returns the problems found (empty = passed).
     */
    public static List<String> validate(File rootDir, List<String> relativePaths) {
        List<String> errors = new ArrayList<>();

        for (String relativePath : relativePaths) {
            File file = new File(rootDir, relativePath);
            if (!file.isFile()) {
                errors.add(relativePath + ": missing after swap");
                continue;
            }

            if (relativePath.endsWith(".json")) {
                try {
                    JSONObject json = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                    if (!json.has("ICD")) errors.add(relativePath + ": not a valid Vulkan ICD manifest");
                }
                catch (IOException | JSONException e) {
                    errors.add(relativePath + ": unreadable (" + e.getMessage() + ")");
                }
            }
        }
        return errors;
    }

    /** Restores the previous files for the given swapped paths (after failed validation). */
    public static boolean rollback(File rootDir, List<String> relativePaths) {
        for (String relativePath : relativePaths) {
            File target = new File(rootDir, relativePath);
            File backup = new File(target.getPath() + BACKUP_SUFFIX);

            if (!StagedInstaller.deleteRecursive(target)) return false;
            if (backup.isFile() && !backup.renameTo(target)) return false;
        }
        return true;
    }

    /** Drops the backup files for the given swapped paths (after successful validation). */
    public static boolean commit(File rootDir, List<String> relativePaths) {
        for (String relativePath : relativePaths) {
            File backup = new File(new File(rootDir, relativePath).getPath() + BACKUP_SUFFIX);
            if (!StagedInstaller.deleteRecursive(backup)) return false;
        }
        return true;
    }

    private static String relativePath(File base, File file) {
        return file.getPath().substring(base.getPath().length() + 1).replace(File.separatorChar, '/');
    }
}
