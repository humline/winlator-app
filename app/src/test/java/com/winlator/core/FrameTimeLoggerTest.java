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

public class FrameTimeLoggerTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void writesCsvPerRun() throws Exception {
        FrameTimeLogger logger = new FrameTimeLogger(folder.getRoot());
        assertTrue(logger.start());

        logger.onFrame(); // records the reference timestamp only
        logger.onFrame();
        logger.onFrame();
        logger.stop();

        File[] files = folder.getRoot().listFiles((dir, name) -> name.endsWith(".csv"));
        assertEquals(1, files.length);

        String content = new String(Files.readAllBytes(files[0].toPath()), StandardCharsets.UTF_8);
        String[] lines = content.split("\n");
        assertEquals("frame,delta_ms,timestamp_ms", lines[0]);
        assertTrue(content, content.contains("# total_frames,2"));
    }

    @Test
    public void onFrameIsNoOpAfterStop() throws Exception {
        FrameTimeLogger logger = new FrameTimeLogger(folder.getRoot());
        logger.start();
        logger.onFrame();
        logger.onFrame();
        logger.stop();

        logger.onFrame(); // must not throw or reopen the file
        logger.stop();

        File[] files = folder.getRoot().listFiles((dir, name) -> name.endsWith(".csv"));
        assertEquals(1, files.length);
    }

    @Test
    public void startFailsGracefullyOnUnwritableDir() throws Exception {
        File blocker = folder.newFile("blocked");
        FrameTimeLogger logger = new FrameTimeLogger(new File(blocker, "sub"));

        assertFalse(logger.start());
        logger.onFrame(); // must stay silent
        logger.stop();
    }

    @Test
    public void framesSurviveWriterDrainBatches() throws Exception {
        FrameTimeLogger logger = new FrameTimeLogger(folder.getRoot());
        assertTrue(logger.start());

        logger.onFrame();
        logger.onFrame();
        Thread.sleep(600); // let the background writer drain the first samples
        logger.onFrame();
        logger.onFrame();
        logger.stop();

        File[] files = folder.getRoot().listFiles((dir, name) -> name.endsWith(".csv"));
        assertEquals(1, files.length);

        // 4 samples produce 3 rows even when written in separate batches
        String content = new String(Files.readAllBytes(files[0].toPath()), StandardCharsets.UTF_8);
        String[] lines = content.split("\n");
        assertEquals(5, lines.length); // header + 3 rows + footer
        assertTrue(content, content.contains("# total_frames,3"));
    }
}
