package com.cpgame.curupira.api;

import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

final class SocketRedisCommands implements RedisCommands {
    private final Socket socket;
    private final BufferedInputStream input;
    private final BufferedOutputStream output;

    private SocketRedisCommands(Socket socket) throws IOException {
        this.socket = socket;
        this.input = new BufferedInputStream(socket.getInputStream());
        this.output = new BufferedOutputStream(socket.getOutputStream());
    }

    static SocketRedisCommands connect(Properties config) throws IOException {
        String host = config.getProperty("redis.host", "18.234.101.161").trim();
        int port = Integer.parseInt(config.getProperty("redis.port", "8021").trim());
        int database = Integer.parseInt(config.getProperty("redis.database", "0").trim());
        if (!"18.234.101.161".equals(host) || port != 8021 || database < 0) {
            throw new IllegalArgumentException("Demo Redis 固定为 18.234.101.161:8021 db=15");
        }
        boolean ssl = Boolean.parseBoolean(config.getProperty("redis.ssl", "false"));
        int connectTimeout = Integer.parseInt(config.getProperty("redis.connect-timeout-ms", "5000"));
        int socketTimeout = Integer.parseInt(config.getProperty("redis.socket-timeout-ms", "30000"));
        Socket socket = ssl ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        socket.connect(new InetSocketAddress(host, port), connectTimeout);
        socket.setSoTimeout(socketTimeout);
        SocketRedisCommands redis = new SocketRedisCommands(socket);
        try {
            String username = config.getProperty("redis.username", "").trim();
            String password = config.getProperty("redis.password", "");
            if (!password.isBlank() && username.isBlank()) redis.command("AUTH", password);
            if (!password.isBlank() && !username.isBlank()) redis.command("AUTH", username, password);
            redis.command("SELECT", Integer.toString(database));
            if (!"PONG".equals(redis.command("PING"))) throw new IOException("Redis PING 未返回 PONG");
            return redis;
        } catch (IOException | RuntimeException failure) {
            try { redis.close(); } catch (IOException ignored) { }
            throw failure;
        }
    }

    @Override public synchronized Object command(String... args) throws IOException {
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
        if (prefix < 0) throw new EOFException("Redis 连接已关闭");
        return switch (prefix) {
            case '+' -> line();
            case '-' -> throw new IOException("Redis error: " + line());
            case ':' -> Long.parseLong(line());
            case '$' -> bulk();
            case '*' -> array();
            default -> throw new IOException("invalid RESP prefix " + (char) prefix);
        };
    }

    private String line() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int current = input.read();
            if (current < 0) throw new EOFException("Redis response truncated");
            if (previous == '\r' && current == '\n') return bytes.toString(StandardCharsets.UTF_8);
            if (previous >= 0) bytes.write(previous);
            previous = current;
        }
    }

    private Object bulk() throws IOException {
        int length = Integer.parseInt(line());
        if (length < 0) return null;
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length || input.read() != '\r' || input.read() != '\n') {
            throw new EOFException("Redis bulk truncated");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private Object array() throws IOException {
        int length = Integer.parseInt(line());
        if (length < 0) return null;
        List<Object> values = new ArrayList<>(length);
        for (int i = 0; i < length; i++) values.add(read());
        return values;
    }

    @Override public void close() throws IOException { socket.close(); }
}
