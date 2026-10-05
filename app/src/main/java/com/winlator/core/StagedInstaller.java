package com.winlator.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.List;

/**
 * State machine for crash-safe staged installation of the rootfs:
 * extract into a staging directory, validate it, then switch it into place
 * while keeping the previous system as a backup until the first successful
 * launch. User data ({@link #PRESERVED_PATHS}) always survives the switch.
 *
 * Pure Java (no Android dependencies) so the switching logic can be validated
 * by plain JVM unit tests (see {@code StagedInstallerTest}).
 */
public abstract class StagedInstaller {
    /** Paths inside the rootfs holding user data that must survive a system update. */
    public static final String[] PRESERVED_PATHS = {"home", "opt/installed-wine"};
    /**
     * Directories that must exist in a freshly extracted rootfs to consider it
     * valid (verified against the shipped rootfs.tzst). {@code .winlator} is
     * intentionally not required: it is created by
     * {@code RootFS.createRFSVersionFile()} after the install, not shipped in
     * the archive.
     */
    public static final String[] REQUIRED_PATHS = {"etc", "opt/wine", "tmp", "home"};
    /** Guards against extracting an empty/truncated archive. */
    public static final int MIN_ENTRY_COUNT = 10;

    public static class ValidationResult {
        public final boolean valid;
        public final List<String> errors;

        public ValidationResult(boolean valid, List<String> errors) {
            this.valid = valid;
            this.errors = errors;
        }
    }

    public static ValidationResult validate(File stagingDir) {
        return validate(stagingDir, -1);
    }

    /**
     * Validates a staged rootfs, optionally against the expected extracted
     * size ({@code expectedSize < 0} = skip the size check) to catch extracts
     * that silently ended early.
     */
    public static ValidationResult validate(File stagingDir, long expectedSize) {
        List<String> errors = new ArrayList<>();
        if (stagingDir == null || !stagingDir.isDirectory()) {
            errors.add("staging directory is missing");
            return new ValidationResult(false, errors);
        }

        for (String path : REQUIRED_PATHS) {
            if (!new File(stagingDir, path).isDirectory()) {
                errors.add("missing required path: " + path);
            }
        }

        if (countEntries(stagingDir) < MIN_ENTRY_COUNT) {
            errors.add("staging directory looks empty or truncated");
        }

        if (expectedSize >= 0 && StorageChecker.dirSize(stagingDir) < expectedSize) {
            errors.add("staging directory is truncated (less content than the archive)");
        }
        return new ValidationResult(errors.isEmpty(), errors);
    }

    /**
     * Atomically-as-possible switches {@code stagingDir} into place at
     * {@code liveDir}, keeping the previous system in {@code backupDir}.
     * On any failure an automatic rollback is attempted and {@code false}
     * is returned.
     */
    public static boolean commit(File liveDir, File stagingDir, File backupDir) {
        ValidationResult result = validate(stagingDir);
        if (!result.valid) return false;

        // an earlier interrupted swap may still hold the only copy of the user
        // data in the backup: restore it first instead of deleting it
        if (backupHoldsUserData(backupDir) && !recoverInterruptedSwap(liveDir, backupDir)) return false;

        deleteRecursive(backupDir); // stale backup from an earlier attempt (user data is in liveDir)

        boolean liveExisted = liveDir.isDirectory();

        // never trust the archive for user data paths; the live data always wins
        for (String path : PRESERVED_PATHS) {
            deleteRecursive(new File(stagingDir, path));
        }

        if (liveExisted && !liveDir.renameTo(backupDir)) return false;

        if (!stagingDir.renameTo(liveDir)) {
            if (liveExisted) backupDir.renameTo(liveDir);
            return false;
        }

        if (liveExisted) {
            for (String path : PRESERVED_PATHS) {
                File source = new File(backupDir, path);
                if (!source.exists()) continue;

                File target = new File(liveDir, path);
                File parent = target.getParentFile();
                if (parent != null) parent.mkdirs();

                if (!source.renameTo(target)) {
                    rollback(liveDir, stagingDir, backupDir);
                    return false;
                }
            }
        }
        else {
            new File(liveDir, "home").mkdirs();
        }
        return true;
    }

    /**
     * Restores the previous system from {@code backupDir}, moving user data
     * back first. Returns {@code false} when there is nothing to roll back to.
     */
    public static boolean rollback(File liveDir, File stagingDir, File backupDir) {
        if (!backupDir.isDirectory()) return false;

        for (String path : PRESERVED_PATHS) {
            File source = new File(liveDir, path);
            File target = new File(backupDir, path);
            if (source.exists() && !target.exists()) {
                File parent = target.getParentFile();
                if (parent != null) parent.mkdirs();
                if (!source.renameTo(target)) return false;
            }
        }

        deleteRecursive(liveDir);
        deleteRecursive(stagingDir);
        return backupDir.renameTo(liveDir);
    }

    /** Drops the backup after the first successful launch of the new system. */
    public static void discardBackup(File backupDir) {
        // safety invariant: never erase the only copy of the user data
        if (backupHoldsUserData(backupDir)) return;
        deleteRecursive(backupDir);
    }

    /**
     * True when the backup still holds preserved user data. A completed
     * {@link #commit(File, File, File)} or {@link #rollback(File, File, File)}
     * always moves the preserved paths out of the backup, so a backup that
     * still holds any of them means a swap/rollback was interrupted while the
     * user data was in transit (and may be the only copy).
     */
    public static boolean backupHoldsUserData(File backupDir) {
        if (backupDir == null || !backupDir.isDirectory()) return false;
        for (String path : PRESERVED_PATHS) {
            if (new File(backupDir, path).exists()) return true;
        }
        return false;
    }

    /**
     * Recovers from a swap or rollback that was interrupted while the user
     * data was in the backup (e.g. a crash between renaming the live rootfs to
     * the backup and switching the staging into place): the data is reunified
     * in the backup and the previous system is restored, so user data is never
     * lost. Deliberately leaves {@code stagingDir} untouched (inside
     * {@link #commit(File, File, File)} it holds the payload being installed).
     * Returns {@code true} when a restore was performed.
     */
    public static boolean recoverInterruptedSwap(File liveDir, File backupDir) {
        if (!backupHoldsUserData(backupDir)) return false;

        // an interrupted preserved-paths move can leave part of the data in
        // the live rootfs: move it back so the restored system holds everything
        for (String path : PRESERVED_PATHS) {
            File source = new File(liveDir, path);
            File target = new File(backupDir, path);
            if (source.exists() && !target.exists()) {
                File parent = target.getParentFile();
                if (parent != null) parent.mkdirs();
                if (!source.renameTo(target)) return false;
            }
        }

        // discard whatever partial content the interrupted swap left behind
        // and restore the previous system (with its user data) as the live one
        if (liveDir.isDirectory() && !deleteRecursive(liveDir)) return false;
        return backupDir.renameTo(liveDir);
    }

    /** Recursively deletes files and directories; symlinks are removed as links. */
    public static boolean deleteRecursive(File file) {
        if (file == null || !Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return true;

        if (Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursive(child)) return false;
                }
            }
        }

        try {
            Files.delete(file.toPath());
            return true;
        }
        catch (IOException e) {
            return false;
        }
    }

    private static int countEntries(File dir) {
        int count = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;

        for (File file : files) {
            count++;
            if (Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                count += countEntries(file);
            }
        }
        return count;
    }
}
