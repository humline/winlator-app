package com.winlator.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * SHA-256 integrity helpers. This class is intentionally pure Java (no Android
 * dependencies) so the verification logic can be validated by plain JVM unit
 * tests (see {@code IntegrityVerifierTest}).
 */
public abstract class IntegrityVerifier {
    public static final String MANIFEST_FILENAME = "manifest.json";
    private static final int BUFFER_SIZE = 8192;

    public static String sha256(File file) throws IOException {
        try (InputStream inStream = new FileInputStream(file)) {
            return sha256(inStream);
        }
    }

    public static String sha256(InputStream inStream) throws IOException {
        MessageDigest digest = newSha256Digest();
        byte[] buffer = new byte[BUFFER_SIZE];
        int length;
        while ((length = inStream.read(buffer)) > 0) {
            digest.update(buffer, 0, length);
        }
        return toHex(digest.digest());
    }

    public static String sha256(byte[] data) {
        return toHex(newSha256Digest().digest(data));
    }

    private static MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toHex(byte[] data) {
        StringBuilder builder = new StringBuilder(data.length * 2);
        for (byte b : data) {
            builder.append(String.format(Locale.ROOT, "%02x", b));
        }
        return builder.toString();
    }

    /**
     * Parses checksum lists in {@code sha256sum} format: {@code <sha256>  <path>}.
     * Blank lines and malformed lines are ignored.
     */
    public static LinkedHashMap<String, String> parseChecksums(String content) {
        LinkedHashMap<String, String> checksums = new LinkedHashMap<>();
        if (content == null) return checksums;

        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+", 2);
            if (parts.length < 2) continue;

            String hash = parts[0].trim().toLowerCase(Locale.ROOT);
            String path = parts[1].trim();
            if (path.startsWith("*")) path = path.substring(1); // binary-mode marker
            if (hash.length() != 64 || path.isEmpty()) continue;
            checksums.put(path, hash);
        }
        return checksums;
    }

    public static LinkedHashMap<String, String> parseChecksums(File checksumFile) throws IOException {
        return parseChecksums(new String(Files.readAllBytes(checksumFile.toPath()), StandardCharsets.UTF_8));
    }
    /**
     * Builds a manifest containing a SHA-256 checksum and size for every regular
     * file below {@code rootDir}. Paths are stored relative to {@code rootDir}
     * using '/' as separator.
     */
    public static JSONObject buildManifest(File rootDir, JSONObject meta) throws IOException {
        try {
            JSONObject manifest = new JSONObject();
            manifest.put("meta", meta != null ? meta : new JSONObject());

            JSONArray entries = new JSONArray();
            collectEntries(rootDir, "", entries);
            manifest.put("files", entries);
            return manifest;
        }
        catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private static void collectEntries(File dir, String basePath, JSONArray entries) throws IOException, JSONException {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            String entryName = basePath + file.getName();
            if (file.isDirectory()) {
                collectEntries(file, entryName + "/", entries);
            }
            else if (file.isFile()) {
                JSONObject entry = new JSONObject();
                entry.put("path", entryName);
                entry.put("size", file.length());
                entry.put("sha256", sha256(file));
                entries.put(entry);
            }
        }
    }

    /**
     * Verifies every manifest entry against the files below {@code rootDir}.
     * Returns the list of problems found (empty list = everything matches).
     */
    public static List<String> verifyManifest(JSONObject manifest, File rootDir) {
        List<String> errors = new ArrayList<>();
        if (manifest == null) {
            errors.add("manifest is missing");
            return errors;
        }

        JSONArray entries = manifest.optJSONArray("files");
        if (entries == null || entries.length() == 0) {
            errors.add("manifest contains no files");
            return errors;
        }

        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) {
                errors.add("manifest entry " + i + " is malformed");
                continue;
            }

            String path = entry.optString("path", "");
            if (path.isEmpty()) {
                errors.add("manifest entry " + i + " has no path");
                continue;
            }

            File file = new File(rootDir, path);
            if (!file.isFile()) {
                errors.add(path + ": missing");
                continue;
            }

            long expectedSize = entry.optLong("size", -1);
            if (expectedSize >= 0 && file.length() != expectedSize) {
                errors.add(path + ": size mismatch (expected " + expectedSize + ", found " + file.length() + ")");
                continue;
            }

            String expectedHash = entry.optString("sha256", "");
            if (!expectedHash.isEmpty()) {
                try {
                    String actualHash = sha256(file);
                    if (!expectedHash.equalsIgnoreCase(actualHash)) {
                        errors.add(path + ": checksum mismatch");
                    }
                }
                catch (IOException e) {
                    errors.add(path + ": unreadable (" + e.getMessage() + ")");
                }
            }
        }
        return errors;
    }

    /** Verifies a {@code sha256sum}-style checksum map against the files below {@code dir}. */
    public static List<String> verifyChecksums(LinkedHashMap<String, String> checksums, File dir) {
        List<String> errors = new ArrayList<>();
        if (checksums == null || checksums.isEmpty()) {
            errors.add("checksum list is empty");
            return errors;
        }

        for (String path : checksums.keySet()) {
            File file = new File(dir, path);
            if (!file.isFile()) {
                errors.add(path + ": missing");
                continue;
            }

            try {
                String actualHash = sha256(file);
                if (!checksums.get(path).equalsIgnoreCase(actualHash)) {
                    errors.add(path + ": checksum mismatch");
                }
            }
            catch (IOException e) {
                errors.add(path + ": unreadable (" + e.getMessage() + ")");
            }
        }
        return errors;
    }
}
