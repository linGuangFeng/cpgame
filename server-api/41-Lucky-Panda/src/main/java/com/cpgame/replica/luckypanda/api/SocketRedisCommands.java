package com.cpgame.replica.luckypanda.api;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import javax.net.ssl.SSLSocketFactory;

/** RESP client. Connection/config failures stay connection errors — never fall back to in-memory deal. */
final class SocketRedisCommands implements RedisCommands {
    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;

    private SocketRedisCommands(Socket socket) throws IOException {
        this.socket = socket;
        this.input = new BufferedInputStream(socket.getInputStream());
        this.output = new BufferedOutputStream(socket.getOutputStream());
    }

    static SocketRedisCommands connect(Properties config) throws IOException {
        String host = config.getProperty("redis.host", "18.234.101.161").trim();
        int port = Integer.parseInt(config.getProperty("redis.port", "8021").trim());
        int database = Integer.parseInt(config.getProperty("redis.database", "0").trim());
        boolean ssl = Boolean.parseBoolean(config.getProperty("redis.ssl", "false"));
        int connectTimeoutMs = Integer.parseInt(config.getProperty("redis.connect-timeout-ms", "5000").trim());
        int socketTimeoutMs = Integer.parseInt(config.getProperty("redis.socket-timeout-ms", "30000").trim());
        String username = config.getProperty("redis.username", "").trim();
        String password = config.getProperty("redis.password", "");
        System.out.printf("REDIS_CONNECTING host=%s port=%d database=%d ssl=%s%n", host, port, database, ssl);
        Socket socket = ssl ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        } catch (IOException refused) {
            throw new IOException("无法连接 Redis %s:%d。先确认网络与 controller.properties 的 redis.host / redis.port / redis.database，不准改成内存出牌。"
                    .formatted(host, port), refused);
        }
        socket.setSoTimeout(socketTimeoutMs);
        SocketRedisCommands connection = new SocketRedisCommands(socket);
        try {
            if (!password.isBlank()) {
                if (username.isBlank()) connection.command("AUTH", password);
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
            throw new IOException("Redis 初始化失败 host=%s port=%d，不准改成内存出牌。".formatted(host, port), ex);
        }
    }

    @Override
    public synchronized Object command(String... args) throws IOException {
        output.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            output.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(bytes);
            output.write('\r');
            output.write('\n');
        }
        output.flush();
        return read();
    }

    private Object read() throws IOException {
        int prefix = input.read();
        if (prefix < 0) throw new EOFException("Redis closed connection");
        return switch (prefix) {
            case '+' -> line();
            case '-' -> throw new IOException("Redis error: " + line());
            case ':' -> Long.parseLong(line());
            case '$' -> bulk();
            case '*' -> array();
            default -> throw new IOException("invalid RESP prefix");
        };
    }

    private String line() throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int current = input.read();
            if (current < 0) throw new EOFException();
            if (previous == '\r' && current == '\n') return bytes.toString(StandardCharsets.UTF_8);
            if (previous >= 0) bytes.write(previous);
            previous = current;
        }
    }

    private Object bulk() throws IOException {
        int length = Integer.parseInt(line());
        if (length < 0) return null;
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length || input.read() != '\r' || input.read() != '\n') throw new EOFException();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private Object array() throws IOException {
        int length = Integer.parseInt(line());
        if (length < 0) return null;
        List<Object> values = new ArrayList<>(length);
        for (int index = 0; index < length; index++) values.add(read());
        return values;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
