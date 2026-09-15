package com.cpgame.sambasensation.redis;

import com.cpgame.sambasensation.generator.GeneratorConfig;

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

/** 无第三方依赖的 RESP2 客户端；网络错误直接上抛，不改变出牌规则。 */
public final class RedisConnection implements AutoCloseable {
    private final Socket socket;
    private final BufferedInputStream input;
    private final BufferedOutputStream output;

    private RedisConnection(Socket socket) throws IOException {
        this.socket = socket;
        input = new BufferedInputStream(socket.getInputStream());
        output = new BufferedOutputStream(socket.getOutputStream());
    }

    public static RedisConnection connect(GeneratorConfig config) throws IOException {
        Socket socket = config.redisSsl ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        socket.connect(new InetSocketAddress(config.redisHost, config.redisPort), config.connectTimeoutMs);
        socket.setSoTimeout(config.socketTimeoutMs);
        RedisConnection connection = new RedisConnection(socket);
        try {
            if (!config.redisPassword.isBlank() && config.redisUsername.isBlank()) connection.command("AUTH", config.redisPassword);
            if (!config.redisPassword.isBlank() && !config.redisUsername.isBlank()) connection.command("AUTH", config.redisUsername, config.redisPassword);
            connection.command("SELECT", Integer.toString(config.redisDatabase));
            Object pong = connection.command("PING");
            if (!"PONG".equals(pong)) throw new IOException("Redis PING returned " + pong);
            return connection;
        } catch (IOException failure) {
            connection.close();
            throw failure;
        }
    }

    public Object command(String... args) throws IOException {
        write(args);
        output.flush();
        return read();
    }

    public List<Object> transaction(List<String[]> commands) throws IOException {
        write(new String[]{"MULTI"});
        for (String[] command : commands) write(command);
        write(new String[]{"EXEC"});
        output.flush();
        Object multi = read();
        if (!"OK".equals(multi)) throw new IOException("Redis MULTI failed: " + multi);
        for (int i = 0; i < commands.size(); i++) {
            Object queued = read();
            if (!"QUEUED".equals(queued)) throw new IOException("Redis command not queued: " + queued);
        }
        Object exec = read();
        if (!(exec instanceof List<?> values) || values.size() != commands.size()) throw new IOException("Redis EXEC response count mismatch");
        return new ArrayList<>((List<?>) exec);
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
            default -> throw new IOException("invalid RESP prefix " + (char) prefix);
        };
    }

    private String line() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
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
        for (int i = 0; i < length; i++) values.add(read());
        return values;
    }

    @Override public void close() throws IOException { socket.close(); }
}
