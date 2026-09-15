package com.cpgame.replica.crazygems;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLSocketFactory;

/** Minimal RESP2 client. */
public final class RedisConnection implements AutoCloseable {
    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;

    private RedisConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.input = new BufferedInputStream(socket.getInputStream());
        this.output = new BufferedOutputStream(socket.getOutputStream());
    }

    public static RedisConnection connect(String host, int port, String username, String password,
                                          int database, boolean ssl, int connectTimeoutMs,
                                          int socketTimeoutMs) throws IOException {
        System.out.printf("REDIS_CONNECTING host=%s port=%d database=%d ssl=%s%n", host, port, database, ssl);
        Socket socket = ssl ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        } catch (IOException refused) {
            throw new IOException("无法连接 Redis %s:%d (%s)。先启动 Redis，或改 host/port。"
                    .formatted(host, port, refused.getMessage() == null ? refused.getClass().getSimpleName()
                            : refused.getMessage()), refused);
        }
        socket.setSoTimeout(socketTimeoutMs);
        RedisConnection connection = new RedisConnection(socket);
        try {
            if (password != null && !password.isBlank()) {
                if (username == null || username.isBlank()) connection.command("AUTH", password);
                else connection.command("AUTH", username, password);
            }
            if (database != 0) connection.command("SELECT", Integer.toString(database));
            Object pong = connection.command("PING");
            if (!"PONG".equals(pong)) throw new IOException("Redis PING returned: " + pong);
            System.out.printf("REDIS_CONNECTED host=%s port=%d database=%d ssl=%s%n", host, port, database, ssl);
            return connection;
        } catch (Exception ex) {
            connection.close();
            if (ex instanceof IOException io) throw io;
            throw new IOException("Redis connection initialization failed", ex);
        }
    }

    public Object command(String... args) throws IOException {
        write(args);
        output.flush();
        return read();
    }

    public List<Object> pipeline(List<String[]> commands) throws IOException {
        for (String[] command : commands) write(command);
        output.flush();
        List<Object> replies = new ArrayList<>(commands.size());
        for (int i = 0; i < commands.size(); i++) replies.add(read());
        Object exec = replies.get(replies.size() - 1);
        if (!(exec instanceof List<?> values) || values.size() != commands.size() - 2) {
            throw new IOException("Redis EXEC response count mismatch");
        }
        return replies;
    }

    private void write(String[] args) throws IOException {
        output.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            output.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(bytes);
            output.write('\r');
            output.write('\n');
        }
    }

    private Object read() throws IOException {
        int prefix = input.read();
        if (prefix < 0) throw new EOFException("Redis closed the connection");
        return switch (prefix) {
            case '+' -> line();
            case '-' -> throw new IOException("Redis error: " + line());
            case ':' -> Long.parseLong(line());
            case '$' -> bulk();
            case '*' -> array();
            default -> throw new IOException("invalid Redis RESP prefix: " + (char) prefix);
        };
    }

    private String line() throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int current = input.read();
            if (current < 0) throw new EOFException();
            if (previous == '\r' && current == '\n') break;
            if (previous >= 0) bytes.write(previous);
            previous = current;
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private Object bulk() throws IOException {
        int length = Integer.parseInt(line());
        if (length < 0) return null;
        byte[] value = input.readNBytes(length);
        if (value.length != length || input.read() != '\r' || input.read() != '\n') throw new EOFException();
        return new String(value, StandardCharsets.UTF_8);
    }

    private Object array() throws IOException {
        int length = Integer.parseInt(line());
        if (length < 0) return null;
        List<Object> result = new ArrayList<>(length);
        for (int i = 0; i < length; i++) result.add(read());
        return result;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
