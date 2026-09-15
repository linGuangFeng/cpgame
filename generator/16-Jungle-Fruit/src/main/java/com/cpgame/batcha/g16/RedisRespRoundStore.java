package com.cpgame.batcha.g16;

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
import java.util.Optional;

/** Small dependency-free RESP2 client for real Redis. */
public final class RedisRespRoundStore implements RedisRoundStore {
    private final Socket socket;
    private final BufferedInputStream input;
    private final BufferedOutputStream output;

    public RedisRespRoundStore(String host, int port, String password, int database,
                               int connectTimeoutMillis, int readTimeoutMillis) throws IOException {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("Redis host is required");
        if (port < 1 || port > 65535 || database < 0) throw new IllegalArgumentException("invalid Redis address/database");
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
        socket.setSoTimeout(readTimeoutMillis);
        input = new BufferedInputStream(socket.getInputStream());
        output = new BufferedOutputStream(socket.getOutputStream());
        if (password != null && !password.isBlank()) expectSimple(command("AUTH", password));
        if (database != 0) expectSimple(command("SELECT", Integer.toString(database)));
    }

    @Override
    public synchronized void writeMember(boolean special, int ratio, byte[] member, int maximumMembers)
            throws IOException {
        if (maximumMembers < 1) throw new IllegalArgumentException("maximumMembers must be positive");
        if (ratio < 0) throw new IllegalArgumentException("ratio must be non-negative");
        String index = RedisKeys.index(special);
        String list = RedisKeys.list(special, ratio);
        String token = Integer.toString(ratio);
        writeCommand(parts("MULTI"));
        expectSimple(readResponse());
        writeCommand(parts("ZADD", index, token, token));
        expectQueued(readResponse());
        writeCommand(parts("RPUSH", list, member));
        expectQueued(readResponse());
        writeCommand(parts("LTRIM", list, Integer.toString(-maximumMembers), "-1"));
        expectQueued(readResponse());
        writeCommand(parts("EXEC"));
        Object response = readResponse();
        if (!(response instanceof List<?> values) || values.size() != 3) {
            throw new IOException("Redis EXEC did not return three results");
        }
    }

    @Override
    public synchronized List<String> ratios(boolean special) throws IOException {
        Object response = command("ZRANGE", RedisKeys.index(special), "0", "-1");
        if (!(response instanceof List<?> members)) throw new IOException("Redis ZRANGE returned an unexpected type");
        List<String> result = new ArrayList<>();
        for (Object member : members) {
            if (member instanceof byte[] bytes) result.add(new String(bytes, StandardCharsets.UTF_8));
            else if (member != null) result.add(member.toString());
        }
        return result;
    }

    @Override
    public synchronized long listLength(boolean special, int ratio) throws IOException {
        Object response = command("LLEN", RedisKeys.list(special, ratio));
        if (response instanceof Long value) return value;
        throw new IOException("Redis LLEN returned an unexpected type");
    }

    @Override
    public synchronized Optional<byte[]> readMember(boolean special, int ratio, int offset) throws IOException {
        Object response = command("LINDEX", RedisKeys.list(special, ratio), Integer.toString(offset));
        if (response == null) return Optional.empty();
        if (response instanceof byte[] bytes) return Optional.of(bytes);
        throw new IOException("Redis LINDEX returned an unexpected type");
    }

    /** Read-only delivery audit used by packaging/acceptance checks. */
    public synchronized long listLength(String poolKey) throws IOException {
        Object response = command("LLEN", poolKey);
        if (response instanceof Long value) return value;
        throw new IOException("Redis LLEN returned an unexpected type");
    }

    /** Read the head member without claiming it. */
    public synchronized Optional<byte[]> peek(String poolKey) throws IOException {
        Object response = command("LINDEX", poolKey, "0");
        if (response == null) return Optional.empty();
        if (response instanceof byte[] bytes) return Optional.of(bytes);
        throw new IOException("Redis LINDEX returned an unexpected type");
    }

    /** Delete one exact key. Used only by the scoped packaging reset utility. */
    public synchronized long delete(String key) throws IOException {
        Object response = command("DEL", key);
        if (response instanceof Long value) return value;
        throw new IOException("Redis DEL returned an unexpected type");
    }

    private Object command(String... values) throws IOException {
        List<byte[]> parts = new ArrayList<>(values.length);
        for (String value : values) parts.add(value.getBytes(StandardCharsets.UTF_8));
        writeCommand(parts);
        return readResponse();
    }

    private void expectSimple(Object response) throws IOException {
        if (!(response instanceof String text) || !"OK".equals(text)) throw new IOException("Redis command was not OK");
    }

    private void expectQueued(Object response) throws IOException {
        if (!(response instanceof String text) || !"QUEUED".equals(text)) throw new IOException("Redis transaction command was not queued");
    }

    private List<byte[]> parts(Object... values) {
        List<byte[]> result = new ArrayList<>(values.length);
        for (Object value : values) {
            result.add(value instanceof byte[] bytes ? bytes : value.toString().getBytes(StandardCharsets.UTF_8));
        }
        return result;
    }

    private void writeCommand(List<byte[]> parts) throws IOException {
        output.write(('*'));
        output.write(Integer.toString(parts.size()).getBytes(StandardCharsets.US_ASCII));
        crlf();
        for (byte[] part : parts) {
            output.write('$');
            output.write(Integer.toString(part.length).getBytes(StandardCharsets.US_ASCII));
            crlf();
            output.write(part);
            crlf();
        }
        output.flush();
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
        if (bytes.length != length || input.read() != '\r' || input.read() != '\n') throw new EOFException("truncated Redis bulk value");
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
    public synchronized void close() throws IOException {
        IOException failure = null;
        try { output.close(); } catch (IOException error) { failure = error; }
        try { input.close(); } catch (IOException error) { if (failure == null) failure = error; }
        try { socket.close(); } catch (IOException error) { if (failure == null) failure = error; }
        if (failure != null) throw failure;
    }
}
