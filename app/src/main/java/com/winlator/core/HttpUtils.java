package com.winlator.core;

import android.app.Activity;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class HttpUtils {
    public static String downloadSync(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection)(new URL(url)).openConnection();
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }

            byte[] bytes;
            try (InputStream inStream = connection.getInputStream()) {
                bytes = StreamUtils.copyToByteArray(inStream);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
        catch (Exception e) {
            return null;
        }
    }

    public static void download(final String url, final Callback<String> onDownloadComplete) {
        Executors.newSingleThreadExecutor().execute(() -> onDownloadComplete.call(downloadSync(url)));
    }

    private static void downloadAsync(String url, File destination, AtomicBoolean interruptRef, Callback<Integer> onPublishProgress, Callback<Boolean> onDownloadComplete) {
        try {
            interruptRef.set(false);
            HttpURLConnection connection = (HttpURLConnection)(new URL(url)).openConnection();
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                onDownloadComplete.call(false);
                return;
            }

            int contentLength = connection.getContentLength();
            try (InputStream inStream = new BufferedInputStream(connection.getInputStream(), StreamUtils.BUFFER_SIZE);
                 OutputStream outStream = new FileOutputStream(destination)) {

                byte[] buffer = new byte[1024];
                int totalSize = 0;
                int bytesRead;
                while ((bytesRead = inStream.read(buffer)) != -1 && !interruptRef.get()) {
                    totalSize += bytesRead;
                    if (onPublishProgress != null) {
                        int progress = (int)(((float)totalSize / contentLength) * 100);
                        onPublishProgress.call(progress);
                    }
                    outStream.write(buffer, 0, bytesRead);
                }

            }

            onDownloadComplete.call(!interruptRef.get());
        }
        catch (Exception e) {
            onDownloadComplete.call(false);
        }
    }

    public static void download(final Activity activity, final String url, final File destination, final Callback<Boolean> onDownloadComplete) {
        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        final AtomicBoolean interruptRef = new AtomicBoolean();
        dialog.show(() -> interruptRef.set(true));
        Executors.newSingleThreadExecutor().execute(() -> {
            downloadAsync(url, destination, interruptRef, (progress) -> {
                activity.runOnUiThread(() -> {
                    dialog.setProgress(progress);
                });
            }, (success) -> {
                if (!success && destination.isFile()) destination.delete();
                activity.runOnUiThread(() -> {
                    dialog.close();
                    onDownloadComplete.call(success);
                });
            });
        });
    }

    public static boolean verifyChecksum(File file, String expectedChecksum) {
        if (expectedChecksum == null || expectedChecksum.isEmpty()) return false;
        String algorithm;
        int len = expectedChecksum.trim().length();
        if (len == 64) {
            algorithm = "SHA-256";
        } else if (len == 32) {
            algorithm = "MD5";
        } else {
            return false;
        }

        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance(algorithm);
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                byte[] byteArray = new byte[1024];
                int bytesCount;
                while ((bytesCount = fis.read(byteArray)) != -1) {
                    digest.update(byteArray, 0, bytesCount);
                }
            }
            byte[] bytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            String calculated = sb.toString();
            return calculated.equalsIgnoreCase(expectedChecksum.trim());
        } catch (Exception e) {
            return false;
        }
    }

    public static String extractChecksum(String content, String algorithm) {
        if (content == null) return null;
        content = content.trim();
        int expectedLength = algorithm.equalsIgnoreCase("SHA-256") ? 64 : 32;
        for (String part : content.split("\\s+")) {
            if (part.length() == expectedLength && part.matches("[0-9a-fA-F]+")) {
                return part.toLowerCase(java.util.Locale.ENGLISH);
            }
        }
        return null;
    }
}
