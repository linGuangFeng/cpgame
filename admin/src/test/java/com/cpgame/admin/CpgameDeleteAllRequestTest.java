package com.cpgame.admin;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;

class CpgameDeleteAllRequestTest {
    @TempDir Path root;

    @Test void legacyOversizedBodyDoesNotHitKeyRequestLimit() throws Exception {
        // Older clients sent all 11507+ bucket keys. Even malformed legacy contents
        // must not determine scope: delete-all only needs the game in the URL.
        byte[] body = ("{\"keys\":[\"BetLog:008001809:000100\"],\"padding\":\""
                + "x".repeat(400_000) + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        verify(body);
    }
    @Test void emptyBodyNeedsNoClientIndexSnapshot() throws Exception { verify(new byte[0]); }

    private void verify(byte[] body) throws Exception {
        var settings = new AdminSettings(root, root.resolve("runtime"), "127.0.0.1", 8000);
        var server = new CpgamePublicLabServer(null, settings);
        Method endpoint = CpgamePublicLabServer.class.getDeclaredMethod("serveBetLogCacheDeleteAll", HttpExchange.class, String.class);
        endpoint.setAccessible(true);
        Exchange exchange = new Exchange(body);
        endpoint.invoke(server, exchange, "1830-Hotpot");
        assertEquals(200, exchange.status);
        assertEquals(0, exchange.request.available());
        String response = exchange.response.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(response.contains("未找到 Redis 配置"), response);
        assertFalse(response.contains("Request too large"));
    }
    private static class Exchange extends HttpExchange {
        final ByteArrayInputStream request;
        final ByteArrayOutputStream response = new ByteArrayOutputStream();
        final Headers headers = new Headers();
        int status;
        Exchange(byte[] body) { request = new ByteArrayInputStream(body); }
        public Headers getRequestHeaders() { return new Headers(); }
        public Headers getResponseHeaders() { return headers; }
        public URI getRequestURI() { return URI.create("/games/1830-Hotpot/betlog/cache/delete-all"); }
        public String getRequestMethod() { return "POST"; }
        public HttpContext getHttpContext() { return null; }
        public void close() { }
        public InputStream getRequestBody() { return request; }
        public OutputStream getResponseBody() { return response; }
        public void sendResponseHeaders(int code, long length) { status=code; }
        public InetSocketAddress getRemoteAddress() { return null; }
        public int getResponseCode() { return status; }
        public InetSocketAddress getLocalAddress() { return null; }
        public String getProtocol() { return "HTTP/1.1"; }
        public Object getAttribute(String name) { return null; }
        public void setAttribute(String name, Object value) { }
        public void setStreams(InputStream input, OutputStream output) { }
        public HttpPrincipal getPrincipal() { return null; }
    }
}
