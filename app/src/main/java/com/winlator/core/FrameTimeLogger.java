package com.winlator.core;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Lightweight frame-time logger: records the delta between rendered frames and
 * writes a CSV per run ({@code frame,delta_ms,timestamp_ms}) so before/after
 * benchmark runs on the same device are repeatable and comparable.
 *
 * Pure Java (no Android dependencies) so the writer logic can be validated by
 * plain JVM unit tests (see {@code FrameTimeLoggerTest}).
 */
public class FrameTimeLogger {
    private final File logDir;
    private final long startNanos = System.nanoTime();
    private FileWriter writer;
    private long lastFrameNanos = 0;
    private long frameCount = 0;
    private boolean broken = false;

    public FrameTimeLogger(File logDir) {
        this.logDir = logDir;
    }

    /** Opens a new timestamped CSV in the log directory. */
    public synchronized boolean start() {
        if (writer != null) return true;
        if (broken) return false;

        try {
            if (!logDir.isDirectory()) logDir.mkdirs();
            String name = "frametimes-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".csv";
            writer = new FileWriter(new File(logDir, name));
            writer.write("frame,delta_ms,timestamp_ms\n");
            return true;
        }
        catch (IOException e) {
            broken = true;
            writer = null;
            return false;
        }
    }

    /** Called once per rendered frame (from the GL thread); cheap and non-blocking. */
    public synchronized void onFrame() {
        if (writer == null || broken) return;

        long now = System.nanoTime();
        if (lastFrameNanos > 0) {
            try {
                writer.write(frameCount+","
                    + String.format(Locale.US, "%.3f", (now - lastFrameNanos) / 1e6) +","
                    + String.format(Locale.US, "%.3f", (now - startNanos) / 1e6) +"\n");
                frameCount++;
                if (frameCount % 120 == 0) writer.flush();
            }
            catch (IOException e) {
                broken = true;
            }
        }
        lastFrameNanos = now;
    }

    /** Finishes the CSV (called on sandbox exit). */
    public synchronized void stop() {
        if (writer != null) {
            try {
                writer.write("# total_frames,"+frameCount+"\n");
                writer.close();
            }
            catch (IOException e) {}
            writer = null;
        }
    }

    public synchronized long getFrameCount() {
        return frameCount;
    }
}
