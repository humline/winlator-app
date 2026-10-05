package com.winlator.core;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Lightweight frame-time logger: records the delta between rendered frames and
 * writes a CSV per run ({@code frame,delta_ms,timestamp_ms}) so before/after
 * benchmark runs on the same device are repeatable and comparable.
 *
 * The render thread only records timestamps ({@link #onFrame()} is O(1) and
 * does no formatting or I/O); formatting and disk writes run on a background
 * writer thread so the logging cannot perturb the measurements.
 *
 * Pure Java (no Android dependencies) so the writer logic can be validated by
 * plain JVM unit tests (see {@code FrameTimeLoggerTest}).
 */
public class FrameTimeLogger {
    /** How often the background writer drains recorded timestamps. */
    private static final long DRAIN_INTERVAL_MS = 250;
    private static final int INITIAL_BUFFER_SIZE = 8192;

    private final File logDir;
    private final long startNanos = System.nanoTime();
    private final Object lock = new Object();

    private FileWriter writer;
    private Thread writerThread;
    private boolean running = false;
    private boolean broken = false;

    private long[] pending = new long[INITIAL_BUFFER_SIZE];
    private int pendingCount = 0;

    private long lastFrameNanos = 0;      // writer thread only
    private volatile long frameCount = 0; // rows written (writer thread only)

    public FrameTimeLogger(File logDir) {
        this.logDir = logDir;
    }

    /** Opens a new timestamped CSV in the log directory and starts the background writer. */
    public boolean start() {
        synchronized (lock) {
            if (writer != null) return true;
            if (broken) return false;

            try {
                if (!logDir.isDirectory()) logDir.mkdirs();
                String name = "frametimes-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".csv";
                writer = new FileWriter(new File(logDir, name));
                writer.write("frame,delta_ms,timestamp_ms\n");
                running = true;
            }
            catch (IOException e) {
                broken = true;
                writer = null;
                return false;
            }
        }

        writerThread = new Thread(this::writeLoop, "FrameTimeLogger-writer");
        writerThread.setDaemon(true);
        writerThread.start();
        return true;
    }

    /** Called once per rendered frame (from the GL thread); O(1): no formatting, no disk I/O. */
    public void onFrame() {
        synchronized (lock) {
            if (!running || broken) return;

            if (pendingCount == pending.length) {
                pending = Arrays.copyOf(pending, pending.length * 2);
            }
            pending[pendingCount++] = System.nanoTime();
            lock.notifyAll();
        }
    }

    /** Finishes the CSV (called on sandbox exit); returns after every recorded frame is written. */
    public void stop() {
        synchronized (lock) {
            if (writer == null) return;
            running = false;
            lock.notifyAll();
        }

        if (writerThread != null) {
            try {
                writerThread.join();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writerThread = null;
        }

        synchronized (lock) {
            if (writer != null) {
                try {
                    writer.write("# total_frames,"+frameCount+"\n");
                    writer.close();
                }
                catch (IOException e) {}
                writer = null;
            }
        }
    }

    public long getFrameCount() {
        return frameCount;
    }

    private void writeLoop() {
        while (true) {
            long[] batch;
            int count;

            synchronized (lock) {
                while (pendingCount == 0 && running && !broken) {
                    try {
                        lock.wait(DRAIN_INTERVAL_MS);
                    }
                    catch (InterruptedException e) {
                        return;
                    }
                }
                if (broken) return;
                if (pendingCount == 0 && !running) return;
                batch = pending;
                count = pendingCount;
                pending = new long[INITIAL_BUFFER_SIZE];
                pendingCount = 0;
            }

            writeBatch(batch, count);
        }
    }

    private void writeBatch(long[] timestamps, int count) {
        try {
            for (int i = 0; i < count; i++) {
                long now = timestamps[i];
                if (lastFrameNanos > 0) {
                    writer.write(frameCount+","
                        + String.format(Locale.US, "%.3f", (now - lastFrameNanos) / 1e6) +","
                        + String.format(Locale.US, "%.3f", (now - startNanos) / 1e6) +"\n");
                    frameCount++;
                }
                lastFrameNanos = now;
            }
            writer.flush();
        }
        catch (IOException e) {
            synchronized (lock) {
                broken = true;
            }
        }
    }
}
