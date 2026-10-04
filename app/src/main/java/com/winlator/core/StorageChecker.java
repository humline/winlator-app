package com.winlator.core;

import java.io.File;

/**
 * Free-space estimation and checks. Pure Java (no Android dependencies) so the
 * policy can be validated by plain JVM unit tests (see {@code StorageCheckerTest}).
 */
public abstract class StorageChecker {
    /** Extra headroom kept free on top of the raw payload size. */
    public static final long HEADROOM_BYTES = 64L * 1024 * 1024;

    public static class Result {
        public final boolean sufficient;
        public final long requiredBytes;
        public final long usableBytes;

        public Result(boolean sufficient, long requiredBytes, long usableBytes) {
            this.sufficient = sufficient;
            this.requiredBytes = requiredBytes;
            this.usableBytes = usableBytes;
        }

        public long missingBytes() {
            return Math.max(0, requiredBytes - usableBytes);
        }
    }

    /** Checks that {@code targetDir} (or its closest existing parent) has {@code requiredBytes} free. */
    public static Result check(File targetDir, long requiredBytes) {
        File probe = targetDir;
        while (probe != null && !probe.isDirectory()) {
            probe = probe.getParentFile();
        }

        long usable = probe != null ? probe.getUsableSpace() : 0;
        return new Result(usable >= requiredBytes, requiredBytes, usable);
    }

    /**
     * A staged rootfs install keeps the live rootfs plus a full staging copy
     * until the switch completes, so roughly twice the extracted size is needed.
     *
     * @param extractedSize total size of the extracted rootfs content
     */
    public static Result checkRootfsInstall(File rootDir, long extractedSize) {
        return check(rootDir, extractedSize * 2 + HEADROOM_BYTES);
    }

    /** An in-place wineprefix update runs wineboot and needs scratch space. */
    public static Result checkWineprefixUpdate(File rootDir, long prefixSize) {
        return check(rootDir, prefixSize / 2 + HEADROOM_BYTES);
    }
}
