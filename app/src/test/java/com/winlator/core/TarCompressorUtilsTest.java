package com.winlator.core;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TarCompressorUtilsTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static class Entry {
        final String name;
        final String content;
        final String linkName;
        final boolean hardLink;

        private Entry(String name, String content, String linkName, boolean hardLink) {
            this.name = name;
            this.content = content;
            this.linkName = linkName;
            this.hardLink = hardLink;
        }

        static Entry file(String name, String content) {
            return new Entry(name, content, null, false);
        }

        static Entry dir(String name) {
            return new Entry(name, null, null, false);
        }

        static Entry symlink(String name, String target) {
            return new Entry(name, null, target, false);
        }

        static Entry hardlink(String name, String target) {
            return new Entry(name, null, target, true);
        }
    }

    private File buildArchive(String name, Entry... entries) throws Exception {
        File archive = folder.newFile(name);
        try (OutputStream outStream = new XZCompressorOutputStream(new FileOutputStream(archive));
             TarArchiveOutputStream tar = new TarArchiveOutputStream(outStream)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            for (Entry entry : entries) {
                if (entry.content != null) {
                    byte[] data = entry.content.getBytes(StandardCharsets.UTF_8);
                    TarArchiveEntry tarEntry = new TarArchiveEntry(entry.name);
                    tarEntry.setSize(data.length);
                    tar.putArchiveEntry(tarEntry);
                    tar.write(data);
                    tar.closeArchiveEntry();
                }
                else if (entry.linkName != null) {
                    TarArchiveEntry tarEntry = new TarArchiveEntry(entry.name, entry.hardLink ? TarConstants.LF_LINK : TarConstants.LF_SYMLINK);
                    tarEntry.setLinkName(entry.linkName);
                    tar.putArchiveEntry(tarEntry);
                    tar.closeArchiveEntry();
                }
                else {
                    TarArchiveEntry tarEntry = new TarArchiveEntry(entry.name + "/");
                    tar.putArchiveEntry(tarEntry);
                    tar.closeArchiveEntry();
                }
            }
            tar.finish();
        }
        return archive;
    }

    @Test
    public void isSafeEntryNameAcceptsRelativePaths() {
        assertTrue(TarCompressorUtils.isSafeEntryName("manifest.json"));
        assertTrue(TarCompressorUtils.isSafeEntryName("container/xuser-1.conf"));
        assertTrue(TarCompressorUtils.isSafeEntryName("./container/xuser-1.conf"));
    }

    @Test
    public void isSafeEntryNameRejectsEscapingPaths() {
        assertFalse(TarCompressorUtils.isSafeEntryName(null));
        assertFalse(TarCompressorUtils.isSafeEntryName(""));
        assertFalse(TarCompressorUtils.isSafeEntryName("../evil.txt"));
        assertFalse(TarCompressorUtils.isSafeEntryName("container/../../evil.txt"));
        assertFalse(TarCompressorUtils.isSafeEntryName("..\\evil.txt"));
        assertFalse(TarCompressorUtils.isSafeEntryName("/evil.txt"));
        assertFalse(TarCompressorUtils.isSafeEntryName("C:/evil.txt"));
    }

    @Test
    public void extractSafeRejectsParentTraversal() throws Exception {
        File archive = buildArchive("traversal.tar.xz", Entry.file("../evil.txt", "pwned"));
        File dest = folder.newFolder("dest");

        assertFalse(TarCompressorUtils.extractSafe(TarCompressorUtils.Type.XZ, archive, dest, 0));
        assertFalse(new File(folder.getRoot(), "evil.txt").exists());
    }

    @Test
    public void extractSafeRejectsAbsoluteEntryName() throws Exception {
        File outside = folder.newFolder("outside");
        File archive = buildArchive("absolute.tar.xz", Entry.file(new File(outside, "evil.txt").getPath(), "pwned"));
        File dest = folder.newFolder("dest");

        assertFalse(TarCompressorUtils.extractSafe(TarCompressorUtils.Type.XZ, archive, dest, 0));
        assertFalse(new File(outside, "evil.txt").exists());
    }

    @Test
    public void extractSafeRejectsSymlinkEntries() throws Exception {
        // a symlink pointing outside plus a write through it would escape
        File archive = buildArchive("symlink.tar.xz",
            Entry.symlink("sub", ".."),
            Entry.file("sub/evil.txt", "pwned"));
        File dest = folder.newFolder("dest");

        assertFalse(TarCompressorUtils.extractSafe(TarCompressorUtils.Type.XZ, archive, dest, 0));
        assertFalse(new File(folder.getRoot(), "evil.txt").exists());
    }

    @Test
    public void extractSafeRejectsHardLinkEntries() throws Exception {
        File archive = buildArchive("hardlink.tar.xz", Entry.hardlink("link", "../evil.txt"));
        File dest = folder.newFolder("dest");

        assertFalse(TarCompressorUtils.extractSafe(TarCompressorUtils.Type.XZ, archive, dest, 0));
    }

    @Test
    public void extractSafeExtractsNormalArchive() throws Exception {
        File archive = buildArchive("normal.tar.xz",
            Entry.dir("container"),
            Entry.file("container/xuser-1.conf", "name=Test"),
            Entry.file("manifest.json", "{}"));
        File dest = folder.newFolder("dest");

        assertTrue(TarCompressorUtils.extractSafe(TarCompressorUtils.Type.XZ, archive, dest, 0));

        File extracted = new File(dest, "container/xuser-1.conf");
        assertTrue(extracted.isFile());
        assertEquals("name=Test", new String(Files.readAllBytes(extracted.toPath()), StandardCharsets.UTF_8));
        assertTrue(new File(dest, "manifest.json").isFile());
    }

    @Test
    public void extractSafeEnforcesTotalSizeCap() throws Exception {
        File archive = buildArchive("capped.tar.xz",
            Entry.file("a.txt", "aaaaa"),
            Entry.file("b.txt", "bbbbb"));
        File dest = folder.newFolder("dest");

        assertFalse(TarCompressorUtils.extractSafe(TarCompressorUtils.Type.XZ, archive, dest, 6));
        assertFalse(new File(dest, "b.txt").exists());

        File dest2 = folder.newFolder("dest2");
        assertTrue(TarCompressorUtils.extractSafe(TarCompressorUtils.Type.XZ, archive, dest2, 100));
        assertTrue(new File(dest2, "b.txt").isFile());
    }

    @Test
    public void extractSafeCreatesMissingParentDirectories() throws Exception {
        // no directory entry precedes the file: parents must be created contained
        File archive = buildArchive("parents.tar.xz", Entry.file("a/b/c.txt", "nested"));
        File dest = folder.newFolder("dest");

        assertTrue(TarCompressorUtils.extractSafe(TarCompressorUtils.Type.XZ, archive, dest, 0));
        assertTrue(new File(dest, "a/b/c.txt").isFile());
    }
}