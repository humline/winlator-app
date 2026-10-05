package com.winlator.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StagedLibSwapTest {
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

    private static final String GOOD_ICD = "{\"ICD\": {\"api_version\": \"1.1.128\", \"library_path\": \"/x/libvulkan_vortek.so\"}}";

    @Test
    public void swapMovesFilesAndBacksUpExistingOnes() throws Exception {
        File staging = folder.newFolder("driver_staging");
        File root = folder.newFolder("rootfs");
        writeFile(staging, "usr/lib/libGL.so.1.7.0", "new-lib");
        writeFile(staging, "usr/share/vulkan/icd.d/vortek_icd.aarch64.json", GOOD_ICD);
        writeFile(root, "usr/lib/libGL.so.1.7.0", "old-lib");

        List<String> moved = StagedLibSwap.swap(staging, root);

        assertEquals(2, moved.size());
        assertTrue(moved.contains("usr/lib/libGL.so.1.7.0"));
        assertEquals("new-lib", readFile(root, "usr/lib/libGL.so.1.7.0"));
        assertEquals("old-lib", readFile(root, "usr/lib/libGL.so.1.7.0" + StagedLibSwap.BACKUP_SUFFIX));
        assertTrue(StagedLibSwap.validate(root, moved).isEmpty());
    }

    @Test
    public void validateFlagsMissingFile() throws Exception {
        File root = folder.newFolder("rootfs");
        List<String> errors = StagedLibSwap.validate(root, java.util.Arrays.asList("usr/lib/libGL.so.1.7.0"));
        assertEquals(1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("missing"));
    }

    @Test
    public void validateFlagsInvalidIcdManifest() throws Exception {
        File root = folder.newFolder("rootfs");
        writeFile(root, "usr/share/vulkan/icd.d/bad.json", "{\"not_icd\": true}");

        List<String> errors = StagedLibSwap.validate(root, java.util.Arrays.asList("usr/share/vulkan/icd.d/bad.json"));
        assertEquals(1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("ICD"));
    }

    @Test
    public void rollbackRestoresPreviousFiles() throws Exception {
        File staging = folder.newFolder("driver_staging");
        File root = folder.newFolder("rootfs");
        writeFile(staging, "usr/lib/libGL.so.1.7.0", "new-lib");
        writeFile(root, "usr/lib/libGL.so.1.7.0", "old-lib");

        List<String> moved = StagedLibSwap.swap(staging, root);
        assertEquals("new-lib", readFile(root, "usr/lib/libGL.so.1.7.0"));

        assertTrue(StagedLibSwap.rollback(root, moved));
        assertEquals("old-lib", readFile(root, "usr/lib/libGL.so.1.7.0"));
        assertFalse(new File(root, "usr/lib/libGL.so.1.7.0" + StagedLibSwap.BACKUP_SUFFIX).exists());
    }

    @Test
    public void rollbackRemovesSwappedFileThatDidNotExistBefore() throws Exception {
        File staging = folder.newFolder("driver_staging");
        File root = folder.newFolder("rootfs");
        writeFile(staging, "usr/lib/libvulkan_vortek.so", "new-lib");

        List<String> moved = StagedLibSwap.swap(staging, root);
        assertTrue(new File(root, "usr/lib/libvulkan_vortek.so").isFile());

        assertTrue(StagedLibSwap.rollback(root, moved));
        assertFalse(new File(root, "usr/lib/libvulkan_vortek.so").exists());
    }

    @Test
    public void commitKeepsNewFilesAndDropsBackups() throws Exception {
        File staging = folder.newFolder("driver_staging");
        File root = folder.newFolder("rootfs");
        writeFile(staging, "usr/lib/libGL.so.1.7.0", "new-lib");
        writeFile(root, "usr/lib/libGL.so.1.7.0", "old-lib");

        List<String> moved = StagedLibSwap.swap(staging, root);
        assertTrue(StagedLibSwap.commit(root, moved));

        assertEquals("new-lib", readFile(root, "usr/lib/libGL.so.1.7.0"));
        assertFalse(new File(root, "usr/lib/libGL.so.1.7.0" + StagedLibSwap.BACKUP_SUFFIX).exists());
    }

    @Test
    public void failedInstallAfterBackupIsRecordedForRollback() throws Exception {
        // staging == root makes the install rename fail deterministically:
        // the target is first renamed to .bak, so the staged file (same path)
        // no longer exists when the install rename runs
        File root = folder.newFolder("rootfs");
        writeFile(root, "libGL.so.1.7.0", "old-lib");

        List<String> moved = new ArrayList<>();
        try {
            StagedLibSwap.swap(root, root, moved);
            fail("expected IOException when the install rename fails");
        }
        catch (IOException expected) {}

        // the path must be recorded although the install failed, otherwise the
        // caller rollback would skip the .bak backup and the library stays missing
        assertEquals(1, moved.size());
        assertEquals("libGL.so.1.7.0", moved.get(0));

        assertTrue(StagedLibSwap.rollback(root, moved));
        assertEquals("old-lib", readFile(root, "libGL.so.1.7.0"));
        assertFalse(new File(root, "libGL.so.1.7.0" + StagedLibSwap.BACKUP_SUFFIX).exists());
    }
}
