package com.winlator.core;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Container/settings backup (export &amp; restore) support.
 *
 * An exported backup is a tar.zst archive with the {@code .wcb} extension
 * containing:
 * <pre>
 * manifest.json   - metadata + SHA-256 checksum of every entry
 * container/      - the container directory (home/xuser-&lt;id&gt;)
 * shortcuts/      - shortcut files belonging to the container
 * </pre>
 *
 * Restore never overwrites existing data: when the preferred target already
 * exists a free {@code -restored} name is picked instead
 * ({@link #resolveConflictTarget(File)}).
 *
 * Pure Java (no Android dependencies) so the policy can be validated by plain
 * JVM unit tests (see {@code BackupManagerTest}); the Android-facing
 * export/restore entry points live at the bottom of this class.
 */
public abstract class BackupManager {
    public static final String EXTENSION = ".wcb";
    public static final String MANIFEST_FILENAME = IntegrityVerifier.MANIFEST_FILENAME;
    public static final String CONTAINER_DIR = "container";
    public static final String SHORTCUTS_DIR = "shortcuts";
    public static final String PROFILES_DIR = "profiles";

    /**
     * Picks a target directory that never overwrites existing data.
     * If {@code preferred} is taken, {@code <name>-restored} is used, then
     * {@code <name>-restored-2}, {@code <name>-restored-3}, ...
     */
    public static File resolveConflictTarget(File preferred) {
        File target = preferred;
        int counter = 0;
        while (target.exists()) {
            counter++;
            String suffix = "-restored" + (counter > 1 ? "-" + counter : "");
            target = new File(preferred.getParentFile(), preferred.getName() + suffix);
        }
        return target;
    }

    /**
     * Verifies an extracted backup directory against its manifest.
     * Returns the list of problems found (empty list = backup is intact).
     */
    public static List<String> verify(File extractedDir) {
        List<String> errors = new ArrayList<>();
        File manifestFile = extractedDir != null ? new File(extractedDir, MANIFEST_FILENAME) : null;

        if (manifestFile == null || !manifestFile.isFile()) {
            errors.add(MANIFEST_FILENAME + " is missing");
            return errors;
        }

        try {
            String content = new String(Files.readAllBytes(manifestFile.toPath()), StandardCharsets.UTF_8);
            JSONObject manifest = new JSONObject(content);
            return IntegrityVerifier.verifyManifest(manifest, extractedDir);
        }
        catch (IOException | RuntimeException e) {
            errors.add(MANIFEST_FILENAME + " is unreadable (" + e.getMessage() + ")");
            return errors;
        }
    }

    /** Builds the manifest for an extracted backup directory (all entries except the manifest itself). */
    public static JSONObject buildManifest(File extractedDir, JSONObject meta) throws IOException {
        return IntegrityVerifier.buildManifest(extractedDir, meta);
    }
}
