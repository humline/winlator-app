package com.winlator.core;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

    /** Writes an octal tar header field (zero-padded, NUL-terminated). */
    private static void writeOctal(byte[] header, int offset, int length, long value) {
        String octal = Long.toString(value, 8);
        String padded = String.format("%" + (length - 1) + "s", octal).replace(' ', '0');
        byte[] digits = padded.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(digits, 0, header, offset, length - 1);
        header[offset + length - 1] = 0;
    }

    /**
     * Hand-crafts a tar header so the entry name is stored verbatim: hostile
     * archives are not built with {@code TarArchiveEntry(String)} (which strips
     * leading slashes), their raw header names reach the extractor unchanged.
     */
    private static byte[] rawTarHeader(String entryName, int size) {
        byte[] header = new byte[512];
        byte[] name = entryName.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, header, 0, Math.min(name.length, 100));

        writeOctal(header, 100, 8, 0644);  // mode
        writeOctal(header, 108, 8, 0);     // uid
        writeOctal(header, 116, 8, 0);     // gid
        writeOctal(header, 124, 12, size);
        writeOctal(header, 136, 12, 0);    // mtime
        header[156] = '0';                 // regular file

        byte[] magic = "ustar".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, header, 257, magic.length);
        header[263] = '0';                 // POSIX version "00"
        header[264] = '0';

        // checksum counts the checksum field itself as spaces
        for (int i = 148; i < 156; i++) header[i] = ' ';
        long sum = 0;
        for (byte b : header) sum += b & 0xff;
        byte[] chksum = String.format("%06o", sum).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(chksum, 0, header, 148, chksum.length);
        header[154] = 0;
        header[155] = ' ';
        return header;
    }

    /** Builds a tar.xz whose single entry keeps {@code entryName} verbatim. */
    private File buildRawNameArchive(String name, String entryName, String content) throws Exception {
        File archive = folder.newFile(name);
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = new XZCompressorOutputStream(new FileOutputStream(archive))) {
            out.write(rawTarHeader(entryName, data.length));
            out.write(data);
            out.write(new byte[(512 - (data.length % 512)) % 512]);
            out.write(new byte[1024]); // end-of-archive blocks
        }
        return archive;
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
        String hostileName = new File(outside, "evil.txt").getPath();
        File archive = buildRawNameArchive("absolute.tar.xz", hostileName, "pwned");
        File dest = folder.newFolder("dest");

        // the crafted archive is well-formed and keeps the hostile name raw
        try (InputStream inStream = new XZCompressorInputStream(new FileInputStream(archive));
             TarArchiveInputStream tar = new TarArchiveInputStream(inStream)) {
            assertEquals(hostileName, tar.getNextTarEntry().getName());
        }

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

    @Test
    public void compressPropagatesIoFailures() throws Exception {
        File blocker = folder.newFile("blocker");
        File destination = new File(blocker, "sub/archive.tar.xz"); // parent is a file

        try {
            TarCompressorUtils.compress(TarCompressorUtils.Type.XZ, folder.getRoot(), destination, 3);
            fail("expected IOException when the destination cannot be written");
        }
        catch (IOException expected) {}
    }
}