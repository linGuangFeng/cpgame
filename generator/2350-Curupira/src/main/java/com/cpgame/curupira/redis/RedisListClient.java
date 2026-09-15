package com.cpgame.curupira.redis;

import com.cpgame.curupira.config.EngineConfiguration;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import javax.net.ssl.SSLSocketFactory;

/** 最小 RESP2 客户端；每批 ZADD、RPUSH、LTRIM 在同一 MULTI/EXEC 内提交。 */
public final class RedisListClient implements AutoCloseable {
    public record Entry(List<String> indexKeys, String listKey, int multiplier, String member) {
        public Entry {
            if (indexKeys == null || indexKeys.isEmpty() || listKey == null || member == null) {
                throw new IllegalArgumentException("Redis entry incomplete");
            }
            indexKeys = List.copyOf(indexKeys);
        }
    }

    private final Socket socket;
    private final int specialCap;
    private final InputStream in;
    private final OutputStream out;

    public RedisListClient(EngineConfiguration config) throws IOException {
        specialCap = config.outputLimits().specialCap;
        socket = config.redisSsl() ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        socket.connect(new InetSocketAddress(config.redisHost(), config.redisPort()), config.redisConnectTimeoutMs());
        socket.setSoTimeout(config.redisSocketTimeoutMs());
        in = new BufferedInputStream(socket.getInputStream());
        out = new BufferedOutputStream(socket.getOutputStream());
        if (!config.redisPassword().isBlank()) {
            if (config.redisUsername().isBlank()) command("AUTH", config.redisPassword());
            else command("AUTH", config.redisUsername(), config.redisPassword());
        }
        if (config.redisDatabase() != 0) command("SELECT", Integer.toString(config.redisDatabase()));
        if (!"PONG".equals(command("PING"))) throw new IOException("Redis PING 失败");
    }

    public void clearGame(int gameId) throws IOException {
        RedisContractGate keys = new RedisContractGate();
        LinkedHashSet<String> doomed = new LinkedHashSet<>(keys.allIndexKeys(gameId));
        for (String pattern : keys.scanPatterns(gameId)) {
            doomed.addAll(scan(pattern));
        }
        List<String> batch = new ArrayList<>();
        for (String key : doomed) {
            if (key == null || key.isBlank()) continue;
            batch.add(key);
            if (batch.size() >= 200) {
                delete(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) delete(batch);
    }

    public void appendBatch(List<Entry> entries, int maximum) throws IOException {
        if (entries.isEmpty()) return;
        List<byte[][]> commands = new ArrayList<>();
        commands.add(args("MULTI"));
        for (Entry entry : entries) {
            String ratio = Integer.toString(entry.multiplier());
            for (String indexKey : entry.indexKeys()) {
                commands.add(args("ZADD", indexKey, ratio, ratio));
            }
            commands.add(new byte[][]{bytes("RPUSH"), bytes(entry.listKey()), bytes(entry.member())});
            int cap = entry.listKey().startsWith("MaryLog:") ? specialCap : maximum;
            commands.add(args("LTRIM", entry.listKey(), "-" + cap, "-1"));
        }
        commands.add(args("EXEC"));
        for (byte[][] command : commands) write(command);
        out.flush();
        List<Object> replies = new ArrayList<>();
        for (int i = 0; i < commands.size(); i++) replies.add(read());
        Object exec = replies.get(replies.size() - 1);
        if (!(exec instanceof List<?> values) || values.size() != commands.size() - 2) {
            throw new IOException("Redis EXEC 返回数量不匹配");
        }
    }

    private List<String> scan(String pattern) throws IOException {
        List<String> found = new ArrayList<>();
        String cursor = "0";
        do {
            Object raw = command("SCAN", cursor, "MATCH", pattern, "COUNT", "200");
            if (!(raw instanceof List<?> parts) || parts.size() != 2) {
                throw new IOException("Redis SCAN 返回值非法");
            }
            cursor = text(parts.get(0));
            if (parts.get(1) instanceof List<?> keys) {
                for (Object item : keys) found.add(text(item));
            }
        } while (!"0".equals(cursor));
        return found;
    }

    private void delete(List<String> keys) throws IOException {
        String[] args = new String[keys.size() + 1];
        args[0] = "DEL";
        for (int i = 0; i < keys.size(); i++) args[i + 1] = keys.get(i);
        command(args);
    }

    private Object command(String... values) throws IOException { write(args(values)); out.flush(); return read(); }
    private static byte[][] args(String... values) {
        byte[][] result = new byte[values.length][];
        for (int i = 0; i < values.length; i++) result[i] = bytes(values[i]);
        return result;
    }
    private void write(byte[][] values) throws IOException {
        out.write(bytes("*" + values.length + "\r\n"));
        for (byte[] value : values) {
            out.write(bytes("$" + value.length + "\r\n")); out.write(value); out.write(bytes("\r\n"));
        }
    }
    private Object read() throws IOException {
        int prefix = in.read();
        if (prefix < 0) throw new EOFException("Redis 关闭连接");
        return switch (prefix) {
            case '+' -> line();
            case '-' -> throw new IOException("Redis 错误：" + line());
            case ':' -> Long.parseLong(line());
            case '$' -> bulk();
            case '*' -> array();
            default -> throw new IOException("非法 RESP 前缀：" + (char) prefix);
        };
    }
    private String line() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int previous = -1;
        while (true) {
            int current = in.read(); if (current < 0) throw new EOFException();
            if (previous == '\r' && current == '\n') break;
            if (previous >= 0) bytes.write(previous); previous = current;
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
    private Object bulk() throws IOException {
        int length = Integer.parseInt(line()); if (length < 0) return null;
        byte[] value = in.readNBytes(length);
        if (value.length != length || in.read() != '\r' || in.read() != '\n') throw new EOFException();
        return value;
    }
    private Object array() throws IOException {
        int length = Integer.parseInt(line()); if (length < 0) return null;
        List<Object> values = new ArrayList<>(length);
        for (int i = 0; i < length; i++) values.add(read());
        return values;
    }
    private static String text(Object value) {
        if (value instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        return String.valueOf(value);
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    @Override public void close() throws IOException { socket.close(); }
}
