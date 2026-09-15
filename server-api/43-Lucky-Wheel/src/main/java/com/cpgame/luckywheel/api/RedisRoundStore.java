package com.cpgame.luckywheel.api;

import com.cpgame.luckywheel.core.CompleteRoundFactory;
import com.cpgame.luckywheel.core.GameRound;
import com.cpgame.luckywheel.core.MinimalFactCodec;
import com.cpgame.luckywheel.core.ResultAnalysis;
import com.cpgame.luckywheel.core.ResultUtil;
import com.cpgame.luckywheel.core.RoundFacts;
import com.cpgame.luckywheel.core.RoundRequest;
import com.cpgame.luckywheel.loader.RedisKeyContract;

import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

/** 每个付费 Round 原子领取一个 Lucky Wheel 最小事实 member，并独立复算后完整投影。 */
final class RedisRoundStore implements AutoCloseable {
    private final RedisConnection redis;
    private final long gameId;
    private final SecureRandom random = new SecureRandom();
    private final MinimalFactCodec codec = new MinimalFactCodec();
    private final CompleteRoundFactory factory = new CompleteRoundFactory();

    private RedisRoundStore(RedisConnection redis, long gameId) { this.redis = redis; this.gameId = gameId; }

    static RedisRoundStore connect(Properties config) throws IOException {
        long gameId = Long.parseLong(config.getProperty("redis.game-id", "8000043").trim());
        if (gameId <= 0) throw new IllegalArgumentException("redis.game-id must be positive");
        return new RedisRoundStore(RedisConnection.connect(config), gameId);
    }

    synchronized GameRound claim(RoundRequest request) throws IOException {
        int betProfile = request.betProfile();
        List<Bucket> lossBuckets = new ArrayList<>();
        List<Bucket> winBuckets = new ArrayList<>();
        collect(false, betProfile, RedisKeyContract.normalIndex(gameId, betProfile), lossBuckets, winBuckets);
        collect(true, betProfile, RedisKeyContract.specialIndex(gameId, betProfile), lossBuckets, winBuckets);
        if (lossBuckets.isEmpty() || winBuckets.isEmpty()) {
            throw new IOException("gid43 Redis中或不中奖池不完整");
        }
        // 合同顺序：先独立随机决定中或不中，再在对应奖池已有的实际倍率桶中随机。
        List<Bucket> selectedOutcome = random.nextBoolean() ? winBuckets : lossBuckets;
        while (!selectedOutcome.isEmpty()) {
            Bucket bucket = selectedOutcome.remove(random.nextInt(selectedOutcome.size()));
            String listKey = bucket.special
                    ? RedisKeyContract.specialList(gameId, bucket.betProfile, bucket.multiplier)
                    : RedisKeyContract.normalList(gameId, bucket.betProfile, bucket.multiplier);
            Object length = redis.command("LLEN", listKey);
            long len = Long.parseLong(String.valueOf(length));
            if (len <= 0) continue;
            Object value = redis.command("LINDEX", listKey, Integer.toString(random.nextInt((int) Math.min(len, Integer.MAX_VALUE))));
            if (value == null) continue;
            String member = value.toString();
            if (!StandardCharsets.US_ASCII.newEncoder().canEncode(member)
                    || member.startsWith("{") || member.startsWith("[")) {
                throw new IOException("缓存member不是极简ASCII事实");
            }
            RoundFacts facts = codec.decodeRedisMember(member.getBytes(StandardCharsets.US_ASCII));
            if (facts.betProfile() != betProfile) throw new IOException("缓存完整局下注档案与隔离池不一致");
            ResultAnalysis analysis = ResultUtil.analyze(facts);
            int actual;
            try { actual = analysis.totalAward().stripTrailingZeros().intValueExact(); }
            catch (ArithmeticException error) { throw new IOException("缓存倍率不是整数", error); }
            boolean special = facts.mode() == 1 || facts.mode() == 2 || facts.mode() == 3;
            if (actual != bucket.multiplier || special != bucket.special || (special && actual == 0)) {
                throw new IOException("缓存member与Redis倍率池分类不一致");
            }
            byte[] keyBytes = new byte[16];
            random.nextBytes(keyBytes);
            return factory.create(request, facts, "LW43-" + HexFormat.of().formatHex(keyBytes));
        }
        throw new IOException("gid43 Redis完整局缓存池为空");
    }

    private void collect(boolean special, int betProfile, String indexKey, List<Bucket> lossTarget,
                         List<Bucket> winTarget) throws IOException {
        Object reply = redis.command("ZRANGE", indexKey, "0", "-1");
        if (!(reply instanceof List<?> values)) return;
        for (Object value : values) {
            int multiplier;
            try { multiplier = Integer.parseInt(String.valueOf(value)); }
            catch (NumberFormatException error) { throw new IOException("Redis倍率不是整数", error); }
            if (multiplier < 0 || (special && multiplier == 0)) continue;
            String listKey = special
                    ? RedisKeyContract.specialList(gameId, betProfile, multiplier)
                    : RedisKeyContract.normalList(gameId, betProfile, multiplier);
            if (Long.parseLong(String.valueOf(redis.command("LLEN", listKey))) > 0) {
                (multiplier == 0 ? lossTarget : winTarget).add(new Bucket(betProfile, special, multiplier));
            }
        }
    }

    @Override public void close() throws IOException { redis.close(); }
    private record Bucket(int betProfile, boolean special, int multiplier) { }

    private static final class RedisConnection implements AutoCloseable {
        private final Socket socket;
        private final InputStream input;
        private final OutputStream output;
        private RedisConnection(Socket socket) throws IOException {
            this.socket = socket;
            input = new BufferedInputStream(socket.getInputStream());
            output = new BufferedOutputStream(socket.getOutputStream());
        }
        static RedisConnection connect(Properties config) throws IOException {
            String host = required(config, "redis.host");
            int port = integer(config, "redis.port");
            int database = integer(config, "redis.database");
            Socket socket = Boolean.parseBoolean(config.getProperty("redis.ssl", "false"))
                    ? SSLSocketFactory.getDefault().createSocket() : new Socket();
            socket.connect(new InetSocketAddress(host, port), integer(config, "redis.connect-timeout-ms"));
            socket.setSoTimeout(integer(config, "redis.socket-timeout-ms"));
            RedisConnection connection = new RedisConnection(socket);
            try {
                String username = config.getProperty("redis.username", "").trim();
                String password = config.getProperty("redis.password", "");
                if (!password.isBlank()) {
                    if (username.isBlank()) connection.command("AUTH", password);
                    else connection.command("AUTH", username, password);
                }
                connection.command("SELECT", Integer.toString(database));
                if (!"PONG".equals(connection.command("PING"))) throw new IOException("Redis PING失败");
                return connection;
            } catch (Exception error) {
                connection.close();
                if (error instanceof IOException io) throw io;
                throw new IOException("Redis连接初始化失败", error);
            }
        }
        synchronized Object command(String... args) throws IOException {
            output.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            for (String arg : args) {
                byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
                output.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
                output.write(bytes); output.write('\r'); output.write('\n');
            }
            output.flush();
            return read();
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
                default -> throw new IOException("非法RESP前缀");
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
        @Override public void close() throws IOException { socket.close(); }
        private static String required(Properties properties, String key) {
            String value = properties.getProperty(key);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("配置缺少" + key);
            return value.trim();
        }
        private static int integer(Properties properties, String key) {
            try { return Integer.parseInt(required(properties, key)); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("配置无效" + key, error); }
        }
    }
}
