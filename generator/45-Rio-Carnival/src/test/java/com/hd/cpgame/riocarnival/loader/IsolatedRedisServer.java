package com.hd.cpgame.riocarnival.loader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 测试专用隔离 Redis：独立临时端口，实现本 Loader 使用的 RESP2 命令与 LIST/ZSET 语义。 */
final class IsolatedRedisServer implements AutoCloseable {
    private final ServerSocket server;
    private final Thread worker;
    private volatile Throwable failure;
    final Map<String, List<String>> lists = Collections.synchronizedMap(new LinkedHashMap<String, List<String>>());
    final Map<String, Set<String>> zsets = Collections.synchronizedMap(new LinkedHashMap<String, Set<String>>());
    final List<List<List<String>>> transactions = Collections.synchronizedList(new ArrayList<List<List<String>>>());

    IsolatedRedisServer() throws IOException {
        server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        worker = new Thread(new Runnable() {
            @Override public void run() {
                try { serve(); } catch (Throwable ex) { if (!server.isClosed()) failure = ex; }
            }
        }, "rio-carnival-isolated-redis");
        worker.setDaemon(true);
        worker.start();
    }

    int port() { return server.getLocalPort(); }

    private void serve() throws IOException {
        try (Socket socket = server.accept();
             InputStream input = new BufferedInputStream(socket.getInputStream());
             OutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
            List<List<String>> queued = null;
            while (!socket.isClosed()) {
                List<String> command;
                try { command = readCommand(input); } catch (EOFException done) { return; }
                String name = command.get(0).toUpperCase(java.util.Locale.ROOT);
                if ("PING".equals(name)) simple(output, "PONG");
                else if ("SELECT".equals(name) || "AUTH".equals(name)) simple(output, "OK");
                else if ("MULTI".equals(name)) { queued = new ArrayList<List<String>>(); simple(output, "OK"); }
                else if ("EXEC".equals(name)) {
                    if (queued == null) throw new IOException("EXEC without MULTI");
                    transactions.add(new ArrayList<List<String>>(queued));
                    output.write(("*" + queued.size() + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    for (List<String> item : queued) integer(output, apply(item));
                    queued = null;
                } else if (queued != null) {
                    queued.add(command);
                    simple(output, "QUEUED");
                } else throw new IOException("unsupported command: " + name);
                output.flush();
            }
        }
    }

    private long apply(List<String> command) {
        String name = command.get(0).toUpperCase(java.util.Locale.ROOT);
        String key = command.get(1);
        if ("ZADD".equals(name)) {
            Set<String> values = zsets.get(key);
            if (values == null) { values = new LinkedHashSet<String>(); zsets.put(key, values); }
            return values.add(command.get(3)) ? 1 : 0;
        }
        if ("RPUSH".equals(name)) {
            List<String> values = lists.get(key);
            if (values == null) { values = new ArrayList<String>(); lists.put(key, values); }
            values.add(command.get(2));
            return values.size();
        }
        if ("LTRIM".equals(name)) {
            List<String> values = lists.get(key);
            if (values == null) return 0;
            int keep = Math.abs(Integer.parseInt(command.get(2)));
            while (values.size() > keep) values.remove(0);
            return 1;
        }
        throw new IllegalArgumentException("unsupported transaction command: " + name);
    }

    private static List<String> readCommand(InputStream input) throws IOException {
        int prefix = input.read();
        if (prefix < 0) throw new EOFException();
        if (prefix != '*') throw new IOException("expected RESP array");
        int size = Integer.parseInt(line(input));
        List<String> command = new ArrayList<String>(size);
        for (int i = 0; i < size; i++) {
            if (input.read() != '$') throw new IOException("expected RESP bulk");
            int length = Integer.parseInt(line(input));
            byte[] value = new byte[length];
            int offset = 0;
            while (offset < length) {
                int read = input.read(value, offset, length - offset);
                if (read < 0) throw new EOFException();
                offset += read;
            }
            if (input.read() != '\r' || input.read() != '\n') throw new EOFException();
            command.add(new String(value, StandardCharsets.UTF_8));
        }
        return command;
    }

    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int current = input.read();
            if (current < 0) throw new EOFException();
            if (previous == '\r' && current == '\n') break;
            if (previous >= 0) bytes.write(previous);
            previous = current;
        }
        return new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static void simple(OutputStream output, String value) throws IOException {
        output.write(("+" + value + "\r\n").getBytes(StandardCharsets.US_ASCII));
    }

    private static void integer(OutputStream output, long value) throws IOException {
        output.write((":" + value + "\r\n").getBytes(StandardCharsets.US_ASCII));
    }

    void assertHealthy() throws InterruptedException {
        if (failure != null) throw new AssertionError("isolated Redis failed", failure);
    }

    @Override public void close() throws Exception {
        server.close();
        worker.join(2000L);
        assertHealthy();
    }
}
