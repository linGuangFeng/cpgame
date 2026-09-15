package com.cpgame.admin;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small dependency-free RESP2 client used by the lab cache inspector. */
final class CpgameRedisClient implements Closeable {
    private final Socket socket;
    private final BufferedInputStream input;
    private final BufferedOutputStream output;

    CpgameRedisClient(String host, int port, String password, int database,
                      int connectTimeoutMillis, int readTimeoutMillis) throws IOException {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("Redis host is required");
        if (port < 1 || port > 65535 || database < 0) throw new IllegalArgumentException("invalid Redis address/database");
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
        socket.setSoTimeout(readTimeoutMillis);
        input = new BufferedInputStream(socket.getInputStream());
        output = new BufferedOutputStream(socket.getOutputStream());
        if (password != null && !password.isBlank()) expectOk(command("AUTH", password));
        if (database != 0) expectOk(command("SELECT", Integer.toString(database)));
    }

    Object command(String... values) throws IOException {
        writeCommand(bytes(values));
        output.flush();
        return readResponse();
    }

    List<Object> pipeline(List<String[]> commands) throws IOException {
        if (commands == null || commands.isEmpty()) return List.of();
        for (String[] values : commands) writeCommand(bytes(values));
        output.flush();
        List<Object> result = new ArrayList<>(commands.size());
        for (int i = 0; i < commands.size(); i++) result.add(readResponse());
        return result;
    }

    long llen(String key) throws IOException {
        Object value = command("LLEN", key);
        return value instanceof Long number ? number : 0L;
    }

    String type(String key) throws IOException {
        Object value = command("TYPE", key);
        return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8)
            : value == null ? "none" : value.toString();
    }

    List<String> scan(String pattern, int limit) throws IOException {
        return cursorCollect("SCAN", null, pattern, limit);
    }

    long zcard(String key) throws IOException {
        Object value = command("ZCARD", key);
        return value instanceof Long number ? number : 0L;
    }

    List<String> lrange(String key, long start, long stop) throws IOException {
        return bulkList(command("LRANGE", key, Long.toString(start), Long.toString(stop)));
    }

    long del(List<String> keys) throws IOException {
        if (keys == null || keys.isEmpty()) return 0;
        long deleted = 0;
        for (int offset = 0; offset < keys.size(); offset += 100) {
            List<String> batch = keys.subList(offset, Math.min(keys.size(), offset + 100));
            List<String> parts = new ArrayList<>(batch.size() + 1);
            parts.add("DEL");
            parts.addAll(batch);
            Object value = command(parts.toArray(String[]::new));
            if (value instanceof Long number) deleted += number;
        }
        return deleted;
    }

    long zrem(String key, List<String> members) throws IOException {
        if (key == null || members == null || members.isEmpty()) return 0;
        List<String> parts = new ArrayList<>(members.size() + 2);
        parts.add("ZREM");
        parts.add(key);
        parts.addAll(members);
        Object value = command(parts.toArray(String[]::new));
        return value instanceof Long number ? number : 0L;
    }

    long srem(String key, List<String> members) throws IOException {
        if (key == null || members == null || members.isEmpty()) return 0;
        List<String> parts = new ArrayList<>(members.size() + 2);
        parts.add("SREM");
        parts.add(key);
        parts.addAll(members);
        Object value = command(parts.toArray(String[]::new));
        return value instanceof Long number ? number : 0L;
    }

    List<String> scanAll(String pattern, int maxKeys) throws IOException {
        if (pattern == null || pattern.isBlank() || maxKeys <= 0) return List.of();
        List<String> values = new ArrayList<>();
        String cursor = "0";
        int rounds = 0;
        do {
            Object raw = command("SCAN", cursor, "MATCH", pattern, "COUNT", "500");
            if (!(raw instanceof List<?> pair) || pair.size() != 2) break;
            cursor = bulk(pair.get(0));
            if (pair.get(1) instanceof List<?> members) {
                for (Object member : members) {
                    String text = bulk(member);
                    if (text != null && !text.isBlank()) values.add(text);
                    if (values.size() >= maxKeys) return values;
                }
            }
        } while (!"0".equals(cursor) && ++rounds < 80);
        return values;
    }

    List<String> zrange(String key, int limit) throws IOException {
        if (limit <= 0) return List.of();
        return bulkList(command("ZRANGE", key, "0", Integer.toString(limit - 1)));
    }

    List<String> zmembers(String key, int limit) throws IOException {
        if (limit <= 0) return List.of();
        List<String> values = bulkList(command("ZRANGE", key, "0", "-1"));
        if (values.size() > limit) return new ArrayList<>(values.subList(0, limit));
        return values;
    }

    String lastZMember(String key) throws IOException {
        List<String> values = bulkList(command("ZRANGE", key, "-1", "-1"));
        return values.isEmpty() ? null : values.get(0);
    }

    List<String> sscan(String key, int limit) throws IOException {
        return cursorCollect("SSCAN", key, null, limit);
    }

    Map<String, Long> memoryUsage(List<String> keys, List<Integer> samples) throws IOException {
        Map<String, Long> result = new LinkedHashMap<>();
        if (keys == null || keys.isEmpty()) return result;
        List<String[]> commands = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            int nested = 1;
            if (samples != null && i < samples.size() && samples.get(i) != null && samples.get(i) > 0) {
                nested = samples.get(i);
            }
            commands.add(new String[]{"MEMORY", "USAGE", keys.get(i), "SAMPLES", Integer.toString(nested)});
        }
        List<Object> values = pipeline(commands);
        for (int i = 0; i < keys.size(); i++) {
            Object value = i < values.size() ? values.get(i) : null;
            result.put(keys.get(i), value instanceof Long number ? number : null);
        }
        return result;
    }

    private List<String> cursorCollect(String commandName, String key, String pattern, int limit)
        throws IOException {
        if (limit <= 0) return List.of();
        List<String> values = new ArrayList<>();
        String cursor = "0";
        int rounds = 0;
        do {
            List<String> parts = new ArrayList<>();
            parts.add(commandName);
            if (key != null) parts.add(key);
            parts.add(cursor);
            if (pattern != null) {
                parts.add("MATCH");
                parts.add(pattern);
            }
            parts.add("COUNT");
            parts.add("200");
            Object raw = command(parts.toArray(String[]::new));
            if (!(raw instanceof List<?> pair) || pair.size() != 2) break;
            cursor = bulk(pair.get(0));
            if (pair.get(1) instanceof List<?> members) {
                for (Object member : members) {
                    String text = bulk(member);
                    if (text != null && !text.isBlank()) values.add(text);
                    if (values.size() >= limit) return values;
                }
            }
        } while (!"0".equals(cursor) && ++rounds < 16);
        return values;
    }

    private List<String> bulkList(Object raw) throws IOException {
        if (!(raw instanceof List<?> values)) return List.of();
        List<String> result = new ArrayList<>(values.size());
        for (Object value : values) {
            String text = bulk(value);
            if (text != null) result.add(text);
        }
        return result;
    }

    private String bulk(Object value) {
        if (value instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        return value == null ? null : value.toString();
    }

    private void expectOk(Object response) throws IOException {
        if (!(response instanceof String text) || !"OK".equals(text)) {
            throw new IOException("Redis command was not OK");
        }
    }

    private List<byte[]> bytes(String... values) {
        List<byte[]> parts = new ArrayList<>(values.length);
        for (String value : values) parts.add(value.getBytes(StandardCharsets.UTF_8));
        return parts;
    }

    private void writeCommand(List<byte[]> parts) throws IOException {
        output.write('*');
        output.write(Integer.toString(parts.size()).getBytes(StandardCharsets.US_ASCII));
        crlf();
        for (byte[] part : parts) {
            output.write('$');
            output.write(Integer.toString(part.length).getBytes(StandardCharsets.US_ASCII));
            crlf();
            output.write(part);
            crlf();
        }
    }

    private Object readResponse() throws IOException {
        int type = input.read();
        if (type < 0) throw new EOFException("Redis closed the connection");
        return switch (type) {
            case '+' -> readLine();
            case '-' -> throw new IOException("Redis error: " + readLine());
            case ':' -> Long.parseLong(readLine());
            case '$' -> readBulk();
            case '*' -> readArray();
            default -> throw new IOException("unknown RESP type: " + (char) type);
        };
    }

    private byte[] readBulk() throws IOException {
        int length = Integer.parseInt(readLine());
        if (length == -1) return null;
        if (length < 0 || length > 64 * 1024 * 1024) throw new IOException("invalid Redis bulk length");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length || input.read() != '\r' || input.read() != '\n') {
            throw new EOFException("truncated Redis bulk value");
        }
        return bytes;
    }

    private List<Object> readArray() throws IOException {
        int length = Integer.parseInt(readLine());
        if (length == -1) return null;
        if (length < 0 || length > 100_000) throw new IOException("invalid Redis array length");
        List<Object> values = new ArrayList<>(length);
        for (int i = 0; i < length; i++) values.add(readResponse());
        return values;
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int value = input.read();
            if (value < 0) throw new EOFException("truncated Redis line");
            if (previous == '\r' && value == '\n') break;
            if (previous >= 0) bytes.write(previous);
            previous = value;
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private void crlf() throws IOException {
        output.write('\r');
        output.write('\n');
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try { output.close(); } catch (IOException error) { failure = error; }
        try { input.close(); } catch (IOException error) { if (failure == null) failure = error; }
        try { socket.close(); } catch (IOException error) { if (failure == null) failure = error; }
        if (failure != null) throw failure;
    }
}
