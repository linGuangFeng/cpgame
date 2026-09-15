package com.cpgame.batchc.cybergo;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLSocketFactory;

/** 最小 RESP2 客户端；每批 ZADD/RPUSH/LTRIM 在同一个 MULTI/EXEC 中提交。 */
public final class RedisConnection implements AutoCloseable {
    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;

    private RedisConnection(Socket socket) throws IOException {
        this.socket = socket;
        input = new BufferedInputStream(socket.getInputStream());
        output = new BufferedOutputStream(socket.getOutputStream());
    }

    public static RedisConnection connect(GeneratorConfig config) throws IOException {
        Socket socket = config.ssl ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        socket.connect(new InetSocketAddress(config.host, config.port), config.connectTimeoutMs);
        socket.setSoTimeout(config.socketTimeoutMs);
        RedisConnection connection = new RedisConnection(socket);
        try {
            if (!config.password.isEmpty()) {
                if (config.username.isEmpty()) connection.command("AUTH", config.password);
                else connection.command("AUTH", config.username, config.password);
            }
            if (config.database != 0) connection.command("SELECT", Integer.toString(config.database));
            if (!"PONG".equals(connection.command("PING"))) throw new IOException("Redis PING返回异常");
            return connection;
        } catch (IOException error) {
            connection.close();
            throw error;
        }
    }

    public Object command(String... args) throws IOException {
        write(args);
        output.flush();
        return read();
    }

    void transaction(List<String[]> body) throws IOException {
        List<String[]> commands = new ArrayList<>(body.size() + 2);
        commands.add(new String[]{"MULTI"});
        commands.addAll(body);
        commands.add(new String[]{"EXEC"});
        for (String[] command : commands) write(command);
        output.flush();
        List<Object> replies = new ArrayList<>(commands.size());
        for (int index = 0; index < commands.size(); index++) replies.add(read());
        Object exec = replies.getLast();
        if (!(exec instanceof List<?> values) || values.size() != body.size())
            throw new IOException("Redis EXEC返回数量与事务命令不一致");
    }

    private void write(String[] args) throws IOException {
        output.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            output.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(bytes);
            output.write('\r'); output.write('\n');
        }
    }

    private Object read() throws IOException {
        int prefix = input.read();
        if (prefix < 0) throw new EOFException("Redis已关闭连接");
        return switch (prefix) {
            case '+' -> line();
            case '-' -> throw new IOException("Redis错误: " + line());
            case ':' -> Long.parseLong(line());
            case '$' -> bulk();
            case '*' -> array();
            default -> throw new IOException("非法RESP前缀: " + (char) prefix);
        };
    }

    private String line() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
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
        for (int index = 0; index < length; index++) result.add(read());
        return result;
    }

    @Override public void close() throws IOException { socket.close(); }
}
