package com.winlator.core;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.fail;

public class StreamUtilsTest {
    @Test
    public void copyCappedCopiesInputWithinTheCap() throws Exception {
        byte[] data = "hello world".getBytes("UTF-8");
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        StreamUtils.copyCapped(new ByteArrayInputStream(data), out, 100);
        assertArrayEquals(data, out.toByteArray());
    }

    @Test
    public void copyCappedAcceptsInputExactlyAtTheCap() throws Exception {
        byte[] data = "hello world".getBytes("UTF-8");
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        StreamUtils.copyCapped(new ByteArrayInputStream(data), out, data.length);
        assertArrayEquals(data, out.toByteArray());
    }

    @Test
    public void copyCappedRejectsInputAboveTheCap() throws Exception {
        byte[] data = new byte[4096];

        try {
            StreamUtils.copyCapped(new ByteArrayInputStream(data), new ByteArrayOutputStream(), 100);
            fail("expected IOException when the input exceeds the cap");
        }
        catch (IOException expected) {}
    }

    @Test
    public void copyCappedAcceptsAnySizeWithoutCap() throws Exception {
        byte[] data = new byte[4096];
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        StreamUtils.copyCapped(new ByteArrayInputStream(data), out, 0);
        assertArrayEquals(data, out.toByteArray());
    }
}