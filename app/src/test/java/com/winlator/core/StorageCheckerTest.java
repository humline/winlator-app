package com.winlator.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StorageCheckerTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void checkPassesForSmallRequirement() {
        StorageChecker.Result result = StorageChecker.check(folder.getRoot(), 1);
        assertTrue(result.sufficient);
        assertEquals(0, result.missingBytes());
    }

    @Test
    public void checkFailsForHugeRequirement() {
        long required = StorageChecker.check(folder.getRoot(), 1).usableBytes + (1024L * 1024 * 1024);
        StorageChecker.Result result = StorageChecker.check(folder.getRoot(), required);

        assertFalse(result.sufficient);
        assertTrue(result.missingBytes() > 0);
    }

    @Test
    public void checkWalksUpToNearestExistingParent() {
        File missing = new File(folder.getRoot(), "does/not/exist");
        StorageChecker.Result result = StorageChecker.check(missing, 1);
        assertTrue(result.sufficient);
    }

    @Test
    public void rootfsInstallRequiresStagingCopyPlusHeadroom() {
        StorageChecker.Result result = StorageChecker.checkRootfsInstall(folder.getRoot(), 1000);
        assertEquals(2000 + StorageChecker.HEADROOM_BYTES, result.requiredBytes);
    }

    @Test
    public void wineprefixUpdateRequiresHeadroomOnTopOfHalfThePrefix() {
        StorageChecker.Result result = StorageChecker.checkWineprefixUpdate(folder.getRoot(), 1000);
        assertEquals(500 + StorageChecker.HEADROOM_BYTES, result.requiredBytes);
    }
}
