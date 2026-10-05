package com.winlator.core;

import com.sun.net.httpserver.HttpServer;

import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class HttpUtilsTest {
    @Test
    public void downloadOptionalSyncDistinguishesMissingChecksumFromServerError() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/checksum", exchange -> {
            byte[] content = "sha256-checksum".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });
        server.createContext("/error", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            assertNull(HttpUtils.downloadOptionalSync(baseUrl + "/missing"));
            assertEquals("sha256-checksum", HttpUtils.downloadOptionalSync(baseUrl + "/checksum"));
            try {
                HttpUtils.downloadOptionalSync(baseUrl + "/error");
                fail("Expected an IOException for server errors");
            } catch (IOException expected) {}
        }
        finally {
            server.stop(0);
        }
    }
}
