package com.winlator.core;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BackupManagerTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File writeFile(File parent, String relPath, String content) throws Exception {
        File file = new File(parent, relPath);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private File buildExtractedBackup() throws Exception {
        File extracted = folder.newFolder("extracted");
        writeFile(extracted, BackupManager.CONTAINER_DIR + "/xuser-1.conf", "name=Test");
        writeFile(extracted, BackupManager.SHORTCUTS_DIR + "/game.shortcut", "shortcut-data");

        JSONObject manifest = BackupManager.buildManifest(extracted, null);
        writeFile(extracted, BackupManager.MANIFEST_FILENAME, manifest.toString());
        return extracted;
    }

    @Test
    public void resolveConflictTargetReturnsPreferredWhenFree() {
        File preferred = new File(folder.getRoot(), "xuser-1");
        assertEquals(preferred, BackupManager.resolveConflictTarget(preferred));
    }

    @Test
    public void resolveConflictTargetNeverOverwritesExistingData() throws Exception {
        File preferred = writeFile(folder.getRoot(), "xuser-1/keep.txt", "original");

        File target = BackupManager.resolveConflictTarget(preferred);
        assertEquals("xuser-1-restored", target.getName());
        assertFalse(target.exists());
        // the existing container is untouched
        assertEquals("original", new String(Files.readAllBytes(preferred.toPath()), StandardCharsets.UTF_8));

        // second restore lands on a fresh name again
        assertTrue(new File(folder.getRoot(), "xuser-1-restored").mkdirs());
        File next = BackupManager.resolveConflictTarget(preferred);
        assertEquals("xuser-1-restored-2", next.getName());
    }

    @Test
    public void verifyAcceptsIntactBackup() throws Exception {
        List<String> errors = BackupManager.verify(buildExtractedBackup());
        assertTrue(errors.toString(), errors.isEmpty());
    }

    @Test
    public void verifyDetectsTamperedBackup() throws Exception {
        File extracted = buildExtractedBackup();
        writeFile(extracted, BackupManager.CONTAINER_DIR + "/xuser-1.conf", "name=Evil");

        List<String> errors = BackupManager.verify(extracted);
        assertFalse(errors.isEmpty());
        assertTrue(errors.toString(), errors.get(0).contains(BackupManager.CONTAINER_DIR + "/xuser-1.conf"));
    }

    @Test
    public void verifyDetectsMissingManifest() throws Exception {
        File extracted = folder.newFolder("no-manifest");
        writeFile(extracted, BackupManager.CONTAINER_DIR + "/xuser-1.conf", "name=Test");

        List<String> errors = BackupManager.verify(extracted);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains(BackupManager.MANIFEST_FILENAME));
    }

    @Test
    public void verifyDetectsCorruptManifest() throws Exception {
        File extracted = folder.newFolder("corrupt-manifest");
        writeFile(extracted, BackupManager.CONTAINER_DIR + "/xuser-1.conf", "name=Test");
        writeFile(extracted, BackupManager.MANIFEST_FILENAME, "{not valid json");

        List<String> errors = BackupManager.verify(extracted);
        assertFalse(errors.isEmpty());
        assertTrue(errors.get(0), errors.get(0).contains("unreadable"));
    }
}
