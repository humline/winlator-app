package com.winlator.core;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IntegrityVerifierTest {
    private static final String SHA256_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File writeFile(File parent, String relPath, String content) throws Exception {
        File file = new File(parent, relPath);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    public void sha256MatchesKnownVector() {
        assertEquals(SHA256_ABC, IntegrityVerifier.sha256("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void sha256OfFileMatchesBytes() throws Exception {
        File file = writeFile(folder.getRoot(), "data.bin", "hello winlator");
        assertEquals(
            IntegrityVerifier.sha256("hello winlator".getBytes(StandardCharsets.UTF_8)),
            IntegrityVerifier.sha256(file)
        );
    }

    @Test
    public void parseChecksumsReadsSha256sumFormat() {
        String content = SHA256_ABC + "  dir/file.txt\n"
            + "\n"
            + "malformed-line-without-path\n"
            + "not-a-hash  other.txt\n";
        LinkedHashMap<String, String> checksums = IntegrityVerifier.parseChecksums(content);

        assertEquals(1, checksums.size());
        assertEquals(SHA256_ABC, checksums.get("dir/file.txt"));
    }

    @Test
    public void parseChecksumsHandlesBinaryMarkerAndNull() {
        LinkedHashMap<String, String> checksums = IntegrityVerifier.parseChecksums(SHA256_ABC + " *file.bin\n");
        assertEquals(SHA256_ABC, checksums.get("file.bin"));

        assertTrue(IntegrityVerifier.parseChecksums(null).isEmpty());
    }

    @Test
    public void manifestRoundTripVerifies() throws Exception {
        writeFile(folder.getRoot(), "a.txt", "aaa");
        writeFile(folder.getRoot(), "sub/b.txt", "bbb");

        JSONObject manifest = IntegrityVerifier.buildManifest(folder.getRoot(), null);
        List<String> errors = IntegrityVerifier.verifyManifest(manifest, folder.getRoot());
        assertTrue(errors.toString(), errors.isEmpty());
    }

    @Test
    public void verifyDetectsTamperedFile() throws Exception {
        File file = writeFile(folder.getRoot(), "a.txt", "aaa");
        JSONObject manifest = IntegrityVerifier.buildManifest(folder.getRoot(), null);

        writeFile(folder.getRoot(), "a.txt", "aab"); // same size, different content
        List<String> errors = IntegrityVerifier.verifyManifest(manifest, folder.getRoot());

        assertEquals(1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("a.txt"));
        assertTrue(errors.get(0), errors.get(0).contains("checksum mismatch"));
        assertEquals("aab", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void verifyDetectsMissingFile() throws Exception {
        writeFile(folder.getRoot(), "a.txt", "aaa");
        JSONObject manifest = IntegrityVerifier.buildManifest(folder.getRoot(), null);

        assertTrue(new File(folder.getRoot(), "a.txt").delete());
        List<String> errors = IntegrityVerifier.verifyManifest(manifest, folder.getRoot());

        assertEquals(1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("missing"));
    }

    @Test
    public void verifyRejectsMissingOrEmptyManifest() {
        assertFalse(IntegrityVerifier.verifyManifest(null, folder.getRoot()).isEmpty());
        assertFalse(IntegrityVerifier.verifyManifest(new JSONObject(), folder.getRoot()).isEmpty());
    }

    @Test
    public void verifyChecksumsDetectsMismatch() throws Exception {
        writeFile(folder.getRoot(), "lib/libGL.so", "lib-content");

        LinkedHashMap<String, String> good = new LinkedHashMap<>();
        good.put("lib/libGL.so", IntegrityVerifier.sha256("lib-content".getBytes(StandardCharsets.UTF_8)));
        assertTrue(IntegrityVerifier.verifyChecksums(good, folder.getRoot()).isEmpty());

        LinkedHashMap<String, String> bad = new LinkedHashMap<>();
        bad.put("lib/libGL.so", SHA256_ABC);
        List<String> errors = IntegrityVerifier.verifyChecksums(bad, folder.getRoot());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("checksum mismatch"));

        assertFalse(IntegrityVerifier.verifyChecksums(new LinkedHashMap<>(), folder.getRoot()).isEmpty());
    }
}
