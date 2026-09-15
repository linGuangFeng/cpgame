package com.cpgame.christmasgift.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Small RESP2 client sufficient for the fixed cache contract. */
public final class RedisClient implements Closeable {
    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;

    public RedisClient(String host, int port, String username, String password, int database,
                       boolean ssl, int connectTimeoutMs, int socketTimeoutMs) throws IOException {
        if (ssl) throw new IOException("TLS is not used by the accepted local Redis contract");
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        socket.setSoTimeout(socketTimeoutMs);
        input = new BufferedInputStream(socket.getInputStream());
        output = new BufferedOutputStream(socket.getOutputStream());
        if (password != null && !password.isBlank()) {
            if (username != null && !username.isBlank()) command("AUTH", username, password);
            else command("AUTH", password);
        }
        command("SELECT", Integer.toString(database));
    }

    public Object command(String... parts) throws IOException {
        write(parts);
        return readReply();
    }

    public List<String> zrange(String key) throws IOException {
        Object reply = command("ZRANGE", key, "0", "-1");
        List<String> values = new ArrayList<>();
        if (reply instanceof List<?> list) for (Object value : list) values.add(String.valueOf(value));
        return values;
    }

    public String lpop(String key) throws IOException {
        Object reply = command("LPOP", key);
        return reply == null ? null : String.valueOf(reply);
    }

    public long llen(String key) throws IOException {
        Object reply = command("LLEN", key);
        return reply instanceof Long n ? n : Long.parseLong(String.valueOf(reply));
    }

    public String lindex(String key, long index) throws IOException {
        Object reply = command("LINDEX", key, Long.toString(index));
        return reply == null ? null : String.valueOf(reply);
    }

    private void write(String... parts) throws IOException {
        output.write(('*' + Integer.toString(parts.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String part : parts) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            output.write(('$' + Integer.toString(bytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(bytes);
            output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        output.flush();
    }

    private Object readReply() throws IOException {
        int type = input.read();
        if (type < 0) throw new EOFException("Redis closed connection");
        String line = readLine();
        return switch (type) {
            case '+' -> line;
            case '-' -> throw new IOException("Redis error: " + line);
            case ':' -> Long.parseLong(line);
            case '$' -> readBulk(Integer.parseInt(line));
            case '*' -> readArray(Integer.parseInt(line));
            default -> throw new IOException("Unknown Redis reply type " + (char) type);
        };
    }

    private String readBulk(int length) throws IOException {
        if (length < 0) return null;
        byte[] value = input.readNBytes(length);
        if (value.length != length) throw new EOFException("short Redis bulk reply");
        expectCrlf();
        return new String(value, StandardCharsets.UTF_8);
    }

    private List<Object> readArray(int count) throws IOException {
        if (count < 0) return null;
        List<Object> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(readReply());
        return result;
    }

    private String readLine() throws IOException {
        StringBuilder value = new StringBuilder();
        int previous = -1;
        while (true) {
            int current = input.read();
            if (current < 0) throw new EOFException("Redis closed connection");
            if (previous == '\r' && current == '\n') {
                value.setLength(value.length() - 1);
                return value.toString();
            }
            value.append((char) current);
            previous = current;
        }
    }

    private void expectCrlf() throws IOException {
        if (input.read() != '\r' || input.read() != '\n') throw new IOException("invalid Redis framing");
    }

    @Override public void close() throws IOException { socket.close(); }
}
