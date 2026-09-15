package com.cpgame.crazy777.generator;

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

final class IsolatedRedisServer implements AutoCloseable {
    private final ServerSocket server;
    private final Thread worker;
    private volatile Throwable failure;
    final Map<String, List<String>> lists = Collections.synchronizedMap(new LinkedHashMap<>());
    final Map<String, Set<String>> zsets = Collections.synchronizedMap(new LinkedHashMap<>());
    final List<List<List<String>>> transactions = Collections.synchronizedList(new ArrayList<>());

    IsolatedRedisServer() throws IOException {
        server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        worker = new Thread(() -> {
            try { serve(); } catch (Throwable ex) { if (!server.isClosed()) failure = ex; }
        }, "crazy777-isolated-redis");
        worker.setDaemon(true);
        worker.start();
    }

    int port() { return server.getLocalPort(); }

    private void serve() throws IOException {
        while (!server.isClosed()) {
            Socket socket;
            try { socket = server.accept(); } catch (IOException closed) { return; }
            Thread t = new Thread(() -> handle(socket), "crazy777-isolated-redis-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private void handle(Socket socket) {
        try (socket;
             InputStream input = new BufferedInputStream(socket.getInputStream());
             OutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
            List<List<String>> queued = null;
            while (!socket.isClosed()) {
                List<String> command;
                try { command = readCommand(input); } catch (EOFException done) { return; }
                String name = command.get(0).toUpperCase(java.util.Locale.ROOT);
                if ("PING".equals(name)) simple(output, "PONG");
                else if ("SELECT".equals(name) || "AUTH".equals(name)) simple(output, "OK");
                else if ("KEYS".equals(name)) output.write("*0\r\n".getBytes(StandardCharsets.US_ASCII));
                else if ("DEL".equals(name)) integer(output, 0);
                else if ("LLEN".equals(name)) {
                    List<String> values = lists.get(command.get(1));
                    integer(output, values == null ? 0 : values.size());
                } else if ("LINDEX".equals(name)) {
                    List<String> values = lists.get(command.get(1));
                    int idx = Integer.parseInt(command.get(2));
                    if (values == null || idx < 0 || idx >= values.size()) bulkNull(output);
                    else bulk(output, values.get(idx));
                } else if ("ZRANGE".equals(name)) {
                    Set<String> values = zsets.get(command.get(1));
                    List<String> items = values == null ? List.of() : new ArrayList<>(values);
                    output.write(("*" + items.size() + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    for (String item : items) bulk(output, item);
                } else if ("MULTI".equals(name)) { queued = new ArrayList<>(); simple(output, "OK"); }
                else if ("EXEC".equals(name)) {
                    if (queued == null) throw new IOException("EXEC without MULTI");
                    transactions.add(new ArrayList<>(queued));
                    output.write(("*" + queued.size() + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    for (List<String> item : queued) integer(output, apply(item));
                    queued = null;
                } else if (queued != null) {
                    queued.add(command);
                    simple(output, "QUEUED");
                } else throw new IOException("unsupported command: " + name);
                output.flush();
            }
        } catch (Throwable ex) {
            if (!server.isClosed()) failure = ex;
        }
    }

    private long apply(List<String> command) {
        String name = command.get(0).toUpperCase(java.util.Locale.ROOT);
        String key = command.get(1);
        if ("ZADD".equals(name))
            return zsets.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(command.get(3)) ? 1 : 0;
        if ("RPUSH".equals(name)) {
            List<String> values = lists.computeIfAbsent(key, k -> new ArrayList<>());
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
        if (input.read() != '*') throw new EOFException();
        int size = Integer.parseInt(line(input));
        List<String> command = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            if (input.read() != '$') throw new IOException("expected RESP bulk");
            int length = Integer.parseInt(line(input));
            byte[] value = input.readNBytes(length);
            if (value.length != length || input.read() != '\r' || input.read() != '\n') throw new EOFException();
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
        return bytes.toString(StandardCharsets.US_ASCII);
    }

    private static void simple(OutputStream output, String value) throws IOException {
        output.write(("+" + value + "\r\n").getBytes(StandardCharsets.US_ASCII));
    }

    private static void integer(OutputStream output, long value) throws IOException {
        output.write((":" + value + "\r\n").getBytes(StandardCharsets.US_ASCII));
    }

    private static void bulkNull(OutputStream output) throws IOException {
        output.write("$-1\r\n".getBytes(StandardCharsets.US_ASCII));
    }

    private static void bulk(OutputStream output, String value) throws IOException {
        byte[] b = value.getBytes(StandardCharsets.UTF_8);
        output.write(("$" + b.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(b);
        output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
    }

    void assertHealthy() { if (failure != null) throw new AssertionError("isolated Redis failed", failure); }

    @Override public void close() throws Exception {
        server.close();
        worker.join(2000L);
        assertHealthy();
    }
}
