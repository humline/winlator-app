package com.winlator.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StagedInstallerTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File writeFile(File parent, String relPath, String content) throws Exception {
        File file = new File(parent, relPath);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private String readFile(File parent, String relPath) throws Exception {
        return new String(Files.readAllBytes(new File(parent, relPath).toPath()), StandardCharsets.UTF_8);
    }

    /** Live system with user data (container saves + installed wine builds). */
    private File buildLiveSystem() throws Exception {
        File live = folder.newFolder("rootfs");
        writeFile(live, "etc/old.conf", "old");
        writeFile(live, "opt/wine/bin/wine", "old-wine");
        writeFile(live, "usr/lib/libEGL.so", "old-lib");
        writeFile(live, "home/xuser-1/game.dat", "save-data");
        writeFile(live, "opt/installed-wine/wine-custom/bin/wine", "custom-wine");
        return live;
    }

    /** Freshly extracted new system (rootfs.tzst equivalent). */
    private File buildStaging() throws Exception {
        File staging = folder.newFolder("rootfs.staging");
        writeFile(staging, "etc/new.conf", "new");
        writeFile(staging, "etc/old.conf", "new-old");
        writeFile(staging, "opt/wine/bin/wine", "new-wine");
        writeFile(staging, "usr/lib/libEGL.so", "new-lib");
        writeFile(staging, "tmp/.keep", "");
        writeFile(staging, "home/.keep", "");
        for (int i = 0; i < 5; i++) writeFile(staging, "usr/share/f" + i, "x");
        return staging;
    }

    @Test
    public void validateAcceptsCompleteStaging() throws Exception {
        StagedInstaller.ValidationResult result = StagedInstaller.validate(buildStaging());
        assertTrue(result.errors.toString(), result.valid);
    }

    @Test
    public void validateRejectsMissingRequiredPath() throws Exception {
        File staging = buildStaging();
        assertTrue(StagedInstaller.deleteRecursive(new File(staging, "opt/wine")));

        StagedInstaller.ValidationResult result = StagedInstaller.validate(staging);
        assertFalse(result.valid);
        assertTrue(result.errors.toString(), result.errors.get(0).contains("opt/wine"));
    }

    @Test
    public void validateRejectsMissingStaging() {
        assertFalse(StagedInstaller.validate(null).valid);
        assertFalse(StagedInstaller.validate(new File(folder.getRoot(), "nope")).valid);
    }

    @Test
    public void validateRejectsTruncatedStaging() throws Exception {
        File staging = folder.newFolder("rootfs.truncated");
        new File(staging, "etc").mkdirs();
        new File(staging, "opt/wine").mkdirs();

        StagedInstaller.ValidationResult result = StagedInstaller.validate(staging);
        assertFalse(result.valid);
        assertTrue(result.errors.toString(), result.errors.toString().contains("empty or truncated"));
    }

    @Test
    public void validateRejectsMissingRequiredUserPath() throws Exception {
        File staging = buildStaging();
        assertTrue(StagedInstaller.deleteRecursive(new File(staging, "tmp")));

        StagedInstaller.ValidationResult result = StagedInstaller.validate(staging);
        assertFalse(result.valid);
        assertTrue(result.errors.toString(), result.errors.toString().contains("tmp"));
    }

    @Test
    public void validateFlagsSizeTruncatedStaging() throws Exception {
        File staging = buildStaging();
        long expectedSize = StorageChecker.dirSize(staging);
        assertTrue(new File(staging, "usr/lib/libEGL.so").delete());

        // without a size expectation the staging still looks complete
        assertTrue(StagedInstaller.validate(staging).valid);

        StagedInstaller.ValidationResult result = StagedInstaller.validate(staging, expectedSize);
        assertFalse(result.valid);
        assertTrue(result.errors.toString(), result.errors.toString().contains("truncated"));
    }
    @Test
    public void commitSwitchesSystemAndPreservesUserData() throws Exception {
        File live = buildLiveSystem();
        File staging = buildStaging();
        File backup = new File(folder.getRoot(), "rootfs.prev");

        assertTrue(StagedInstaller.commit(live, staging, backup));

        // new system is live
        assertEquals("new", readFile(live, "etc/new.conf"));
        assertEquals("new-wine", readFile(live, "opt/wine/bin/wine"));

        // user data survived the switch
        assertEquals("save-data", readFile(live, "home/xuser-1/game.dat"));
        assertEquals("custom-wine", readFile(live, "opt/installed-wine/wine-custom/bin/wine"));

        // staging is gone, backup holds the old system without the user data
        assertFalse(staging.exists());
        assertTrue(backup.isDirectory());
        assertEquals("old", readFile(backup, "etc/old.conf"));
        assertFalse(new File(backup, "home").exists());
        assertFalse(new File(backup, "opt/installed-wine").exists());
    }

    @Test
    public void commitRejectsIncompleteStagingWithoutTouchingLive() throws Exception {
        File live = buildLiveSystem();
        File staging = buildStaging();
        assertTrue(StagedInstaller.deleteRecursive(new File(staging, "opt/wine")));
        File backup = new File(folder.getRoot(), "rootfs.prev");

        assertFalse(StagedInstaller.commit(live, staging, backup));

        // live system completely untouched (fault-injection: interrupted install)
        assertEquals("old", readFile(live, "etc/old.conf"));
        assertEquals("save-data", readFile(live, "home/xuser-1/game.dat"));
        assertEquals("custom-wine", readFile(live, "opt/installed-wine/wine-custom/bin/wine"));
        assertFalse(backup.exists());
    }

    @Test
    public void rollbackRestoresPreviousSystem() throws Exception {
        File live = buildLiveSystem();
        File staging = buildStaging();
        File backup = new File(folder.getRoot(), "rootfs.prev");
        assertTrue(StagedInstaller.commit(live, staging, backup));

        assertTrue(StagedInstaller.rollback(live, staging, backup));

        assertEquals("old", readFile(live, "etc/old.conf"));
        assertEquals("old-wine", readFile(live, "opt/wine/bin/wine"));
        assertEquals("save-data", readFile(live, "home/xuser-1/game.dat"));
        assertEquals("custom-wine", readFile(live, "opt/installed-wine/wine-custom/bin/wine"));
        assertFalse(backup.exists());
    }

    @Test
    public void rollbackWithoutBackupFails() throws Exception {
        File live = buildLiveSystem();
        File staging = buildStaging();
        File backup = new File(folder.getRoot(), "rootfs.prev");

        assertFalse(StagedInstaller.rollback(live, staging, backup));
        assertEquals("old", readFile(live, "etc/old.conf"));
    }

    @Test
    public void discardBackupRemovesBackupAfterSuccessfulLaunch() throws Exception {
        File live = buildLiveSystem();
        File staging = buildStaging();
        File backup = new File(folder.getRoot(), "rootfs.prev");
        assertTrue(StagedInstaller.commit(live, staging, backup));

        StagedInstaller.discardBackup(backup);

        assertFalse(backup.exists());
        assertEquals("new", readFile(live, "etc/new.conf"));
        assertEquals("save-data", readFile(live, "home/xuser-1/game.dat"));
    }

    @Test
    public void commitOnFreshInstallCreatesLiveSystem() throws Exception {
        File live = new File(folder.getRoot(), "rootfs-fresh");
        File staging = buildStaging();
        File backup = new File(folder.getRoot(), "rootfs.prev");

        assertTrue(StagedInstaller.commit(live, staging, backup));

        assertEquals("new", readFile(live, "etc/new.conf"));
        assertTrue(new File(live, "home").isDirectory());
        assertFalse(staging.exists());
        assertFalse(backup.exists());
    }

    @Test
    public void recoverInterruptedSwapRestoresSystemAndDataAfterCrashBetweenRenames() throws Exception {
        File live = buildLiveSystem();
        File staging = buildStaging();
        File backup = new File(folder.getRoot(), "rootfs.prev");

        // crash right after the live rootfs was renamed to the backup: the
        // system and the only copy of the user data live in the backup
        assertTrue(live.renameTo(backup));
        assertFalse(live.exists());

        assertTrue(StagedInstaller.recoverInterruptedSwap(live, backup));

        // previous system and user data restored as the live rootfs
        assertEquals("old", readFile(live, "etc/old.conf"));
        assertEquals("save-data", readFile(live, "home/xuser-1/game.dat"));
        assertEquals("custom-wine", readFile(live, "opt/installed-wine/wine-custom/bin/wine"));
        assertFalse(backup.exists());

        // the staged payload is left untouched for the retried install
        assertEquals("new", readFile(staging, "etc/new.conf"));
    }

    @Test
    public void recoverInterruptedSwapReunifiesPartiallyMovedData() throws Exception {
        File live = buildLiveSystem();
        File staging = buildStaging();
        File backup = new File(folder.getRoot(), "rootfs.prev");

        // swap interrupted in the middle of the preserved-paths move: home
        // already arrived in the new live rootfs, installed-wine did not
        assertTrue(StagedInstaller.deleteRecursive(new File(staging, "home")));
        assertTrue(live.renameTo(backup));
        assertTrue(staging.renameTo(live));
        assertTrue(new File(backup, "home").renameTo(new File(live, "home")));

        assertTrue(StagedInstaller.recoverInterruptedSwap(live, backup));

        // both preserved paths are back together in the restored system
        assertEquals("old", readFile(live, "etc/old.conf"));
        assertEquals("save-data", readFile(live, "home/xuser-1/game.dat"));
        assertEquals("custom-wine", readFile(live, "opt/installed-wine/wine-custom/bin/wine"));
        assertFalse(backup.exists());
    }

    @Test
    public void recoverInterruptedSwapIgnoresPendingBackupWithoutUserData() throws Exception {
        File live = buildLiveSystem();
        File staging = buildStaging();
        File backup = new File(folder.getRoot(), "rootfs.prev");

        // normal post-commit state: the backup holds the old system while the
        // user data already lives in the live rootfs
        assertTrue(StagedInstaller.commit(live, staging, backup));
        assertFalse(StagedInstaller.backupHoldsUserData(backup));

        assertFalse(StagedInstaller.recoverInterruptedSwap(live, backup));
        assertTrue(live.isDirectory());
        assertTrue(backup.isDirectory()); // kept until the first successful launch
        assertEquals("save-data", readFile(live, "home/xuser-1/game.dat"));
    }

    @Test
    public void commitAfterInterruptedSwapNeverDeletesTheOnlyCopyOfUserData() throws Exception {
        File live = buildLiveSystem();
        File staging = buildStaging();
        File backup = new File(folder.getRoot(), "rootfs.prev");

        // crash between the renames: system and user data exist only in the backup
        assertTrue(live.renameTo(backup));

        // the retried install reaches commit() again with a fresh payload
        assertTrue(StagedInstaller.commit(live, staging, backup));

        // the new system is live and the user data survived
        assertEquals("new", readFile(live, "etc/new.conf"));
        assertEquals("save-data", readFile(live, "home/xuser-1/game.dat"));
        assertEquals("custom-wine", readFile(live, "opt/installed-wine/wine-custom/bin/wine"));
    }

    @Test
    public void discardBackupKeepsBackupThatStillHoldsUserData() throws Exception {
        File live = buildLiveSystem();
        File backup = new File(folder.getRoot(), "rootfs.prev");

        // interrupted swap: the backup holds the only copy of the user data
        assertTrue(live.renameTo(backup));

        StagedInstaller.discardBackup(backup);

        assertTrue(backup.isDirectory()); // refused to erase the only copy
        assertEquals("save-data", readFile(backup, "home/xuser-1/game.dat"));
    }
}
