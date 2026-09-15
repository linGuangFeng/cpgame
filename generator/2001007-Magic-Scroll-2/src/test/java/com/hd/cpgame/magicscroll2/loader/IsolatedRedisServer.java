package com.hd.cpgame.magicscroll2.loader;

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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 测试专用隔离 Redis：独立临时端口，实现 Loader 所需 RESP2、LIST、ZSET 与事务语义。 */
final class IsolatedRedisServer implements AutoCloseable {
    private final ServerSocket server;
    private final Thread worker;
    private volatile Throwable failure;
    final Map<String, List<String>> lists =
            Collections.synchronizedMap(new LinkedHashMap<String, List<String>>());
    final Map<String, Set<String>> zsets =
            Collections.synchronizedMap(new LinkedHashMap<String, Set<String>>());
    final List<List<List<String>>> transactions =
            Collections.synchronizedList(new ArrayList<List<List<String>>>());

    IsolatedRedisServer() throws IOException {
        server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        worker = new Thread(new Runnable() {
            @Override public void run() {
                try { serve(); }
                catch (Throwable ex) { if (!server.isClosed()) failure = ex; }
            }
        }, "magic-scroll2-isolated-redis");
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
                try { command = readCommand(input); }
                catch (EOFException done) { return; }
                String name = command.get(0).toUpperCase(Locale.ROOT);
                if ("PING".equals(name)) simple(output, "PONG");
                else if ("SELECT".equals(name) || "AUTH".equals(name)) simple(output, "OK");
                else if ("KEYS".equals(name)) {
                    List<String> found = matchKeys(command.get(1));
                    output.write(("*" + found.size() + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    for (String key : found) bulk(output, key);
                } else if ("DEL".equals(name)) {
                    int removed = 0;
                    for (int i = 1; i < command.size(); i++) {
                        if (lists.remove(command.get(i)) != null) removed++;
                        if (zsets.remove(command.get(i)) != null) removed++;
                    }
                    integer(output, removed);
                } else if ("LLEN".equals(name)) {
                    List<String> values = lists.get(command.get(1));
                    integer(output, values == null ? 0 : values.size());
                } else if ("LINDEX".equals(name)) {
                    List<String> values = lists.get(command.get(1));
                    int idx = Integer.parseInt(command.get(2));
                    if (values == null || idx < 0 || idx >= values.size()) bulkNull(output);
                    else bulk(output, values.get(idx));
                } else if ("ZRANGE".equals(name)) {
                    java.util.Set<String> values = zsets.get(command.get(1));
                    List<String> items = values == null ? new ArrayList<String>() : new ArrayList<String>(values);
                    output.write(("*" + items.size() + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    for (String item : items) bulk(output, item);
                } else if ("MULTI".equals(name)) {
                    queued = new ArrayList<List<String>>();
                    simple(output, "OK");
                } else if ("EXEC".equals(name)) {
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
        String name = command.get(0).toUpperCase(Locale.ROOT);
        String key = command.get(1);
        if ("ZADD".equals(name)) {
            Set<String> values = zsets.get(key);
            if (values == null) {
                values = new LinkedHashSet<String>();
                zsets.put(key, values);
            }
            return values.add(command.get(3)) ? 1 : 0;
        }
        if ("RPUSH".equals(name)) {
            List<String> values = lists.get(key);
            if (values == null) {
                values = new ArrayList<String>();
                lists.put(key, values);
            }
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
        List<String> command = new ArrayList<String>(size);
        for (int i = 0; i < size; i++) {
            if (input.read() != '$') throw new IOException("expected RESP bulk");
            int length = Integer.parseInt(line(input));
            byte[] value = new byte[length];
            int offset = 0;
            while (offset < length) {
                int count = input.read(value, offset, length - offset);
                if (count < 0) throw new EOFException();
                offset += count;
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

    private static void bulk(OutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(bytes);
        output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
    }

    private static void bulkNull(OutputStream output) throws IOException {
        output.write("$-1\r\n".getBytes(StandardCharsets.US_ASCII));
    }

    private List<String> matchKeys(String pattern) {
        String prefix = pattern.endsWith("*") ? pattern.substring(0, pattern.length() - 1) : pattern;
        LinkedHashSet<String> found = new LinkedHashSet<String>();
        for (String key : lists.keySet()) {
            if (pattern.endsWith("*") ? key.startsWith(prefix) : key.equals(pattern)) found.add(key);
        }
        for (String key : zsets.keySet()) {
            if (pattern.endsWith("*") ? key.startsWith(prefix) : key.equals(pattern)) found.add(key);
        }
        return new ArrayList<String>(found);
    }

    void assertHealthy() {
        if (failure != null) throw new AssertionError("isolated Redis failed", failure);
    }

    @Override public void close() throws Exception {
        server.close();
        worker.join(2000L);
        assertHealthy();
    }
}
