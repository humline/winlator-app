package com.winlator.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class StreamUtils {
    public static final int BUFFER_SIZE = 64 * 1024;

    public interface ProgressListener {
        void onProgress(long bytesProcessed, long totalBytes);
    }

    public static int skip(InputStream inStream, int bytesToSkip) {
        try {
            int bytesSkipped = (int)inStream.skip(bytesToSkip);

            if (bytesSkipped > 0 && bytesSkipped != bytesToSkip) {
                final byte[] skipBuffer = new byte[1024];

                int bytesRead;
                while (bytesSkipped != bytesToSkip) {
                    bytesRead = inStream.read(skipBuffer, 0, Math.min(skipBuffer.length, bytesToSkip - bytesSkipped));
                    if (bytesRead == -1) break;
                    bytesSkipped += bytesRead;
                }
            }
            return bytesSkipped;
        }
        catch (IOException e) {
            return 0;
        }
    }

    public static byte[] copyToByteArray(InputStream inStream) {
        if (inStream == null) return new byte[0];

        ByteArrayOutputStream outStream = new ByteArrayOutputStream(BUFFER_SIZE);
        copy(inStream, outStream);
        return outStream.toByteArray();
    }

    public static boolean copy(InputStream inStream, OutputStream outStream) {
        try {
            byte[] buffer = new byte[BUFFER_SIZE];
            int amountRead;
            while ((amountRead = inStream.read(buffer)) != -1) {
                outStream.write(buffer, 0, amountRead);
            }
            outStream.flush();
            return true;
        }
        catch (IOException e) {
            return false;
        }
    }

    /**
     * Copies at most {@code maxBytes} bytes ({@code <= 0} = unlimited) and
     * throws {@link IOException} when the input exceeds the cap, so untrusted
     * or truncated sources cannot consume unbounded disk space.
     */
    public static void copyCapped(InputStream inStream, OutputStream outStream, long maxBytes) throws IOException {
        copyCapped(inStream, outStream, maxBytes, -1, null);
    }

    public static void copyCapped(InputStream inStream, OutputStream outStream, long maxBytes, long totalBytes, ProgressListener listener) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int amountRead;
        while ((amountRead = inStream.read(buffer)) != -1) {
            total += amountRead;
            if (maxBytes > 0 && total > maxBytes) throw new IOException("input exceeds " + maxBytes + " bytes");
            outStream.write(buffer, 0, amountRead);
            if (listener != null) listener.onProgress(total, totalBytes);
        }
        outStream.flush();
    }
}
