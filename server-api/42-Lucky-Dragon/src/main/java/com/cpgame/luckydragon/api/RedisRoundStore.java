package com.cpgame.luckydragon.api;

import com.cpgame.demo.redis.RedisFloorLookup;

import com.cpgame.luckydragon.core.GameRuleCore;
import com.cpgame.luckydragon.core.IndependentRoundVerifier;
import com.cpgame.luckydragon.core.MinimalFactCodec;
import com.cpgame.luckydragon.core.OutcomeType;
import com.cpgame.luckydragon.core.ResultUtil;
import com.cpgame.luckydragon.core.RoundFacts;
import com.cpgame.luckydragon.core.RoundRequest;
import com.cpgame.luckydragon.core.SpinResult;
import com.cpgame.luckydragon.loader.RedisKeyContract;

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
import java.util.List;
import java.util.Properties;

/** Atomically claims one pre-generated complete Round member for each paid Round start. */
final class RedisRoundStore implements LuckyDragonService.RoundProvider {
    private final RedisConnection redis;
    private final long gameId;
    private final SecureRandom random = new SecureRandom();
    private final GameRuleCore rules = new GameRuleCore();
    private final IndependentRoundVerifier verifier = new IndependentRoundVerifier(rules);
    private final MinimalFactCodec codec = new MinimalFactCodec();

    private RedisRoundStore(RedisConnection redis, long gameId) {
        this.redis = redis;
        this.gameId = gameId;
    }

    static RedisRoundStore connect(Properties config) throws IOException {
        long gameId = Long.parseLong(config.getProperty("redis.game-id", "8000042").trim());
        if (gameId <= 0) throw new IllegalArgumentException("redis.game-id must be positive");
        return new RedisRoundStore(RedisConnection.connect(config), gameId);
    }

    @Override
    public synchronized LuckyDragonService.ClaimedRound claim(RoundRequest request) throws IOException {
        boolean wantWin = random.nextBoolean();
        boolean firstSpecial = wantWin && random.nextBoolean();
        for (boolean pool : wantWin ? new boolean[]{firstSpecial, !firstSpecial} : new boolean[]{false}) {
            var cursor = RedisFloorLookup.open(redis::command, pool ? RedisKeyContract.specialIndex(gameId) : RedisKeyContract.normalIndex(gameId),
                    m -> pool ? RedisKeyContract.specialList(gameId, m) : RedisKeyContract.normalList(gameId, m), random,
                    wantWin ? 1 : 0, wantWin ? Integer.MAX_VALUE : 0);
            Integer multiplier;
            while ((multiplier = cursor.next()) != null) {
            Bucket bucket = new Bucket(pool, multiplier);
            String key = bucket.special
                ? RedisKeyContract.specialList(gameId, bucket.multiplier)
                : RedisKeyContract.normalList(gameId, bucket.multiplier);
            Object length = redis.command("LLEN", key);
            long len = Long.parseLong(String.valueOf(length));
            if (len <= 0) continue;
            Object value = redis.command("LINDEX", key, Integer.toString(random.nextInt((int) Math.min(len, Integer.MAX_VALUE))));
            if (value == null) continue;
            String member = value.toString();
            if (!StandardCharsets.US_ASCII.newEncoder().canEncode(member)
                || member.startsWith("{") || member.startsWith("[")) {
                throw new IOException("cached member is not minimal ASCII");
            }
            RoundFacts cached = codec.decode(member.getBytes(StandardCharsets.US_ASCII));
            if (cached.deliveryIndex() != 0 || !cached.terminal()) {
                throw new IOException("gid42 cached Round is not one terminal delivery");
            }
            RoundFacts projected = new RoundFacts(request.betSize(), request.betLevel(), cached.symbols(),
                cached.reelMultiplier(), cached.roundKey(), cached.deliveryIndex(), cached.terminal());
            SpinResult result = ResultUtil.analyze(rules, projected);
            verifier.verify(request, result);
            int actual = ResultUtil.positiveMultiplier(rules, projected);
            boolean isSpecial = result.outcome() == OutcomeType.WILD_MULTIPLIER_X3
                || result.outcome() == OutcomeType.WILD_MULTIPLIER_X5
                || result.outcome() == OutcomeType.WILD_MULTIPLIER_X9;
            if (actual != bucket.multiplier || isSpecial != bucket.special) {
                throw new IOException("cached member classification does not match Redis bucket");
            }
            return new LuckyDragonService.ClaimedRound(cached.roundKey(), result, member);
        }
        }
        throw new IOException("selected Redis WIN/LOSS side became empty for raw gid 42");
    }

    

    private record Bucket(boolean special, int multiplier) { }

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
            boolean ssl = Boolean.parseBoolean(config.getProperty("redis.ssl", "false"));
            int connectTimeout = integer(config, "redis.connect-timeout-ms");
            int socketTimeout = integer(config, "redis.socket-timeout-ms");
            Socket socket = ssl ? SSLSocketFactory.getDefault().createSocket() : new Socket();
            socket.connect(new InetSocketAddress(host, port), connectTimeout);
            socket.setSoTimeout(socketTimeout);
            RedisConnection connection = new RedisConnection(socket);
            try {
                String username = config.getProperty("redis.username", "").trim();
                String password = config.getProperty("redis.password", "");
                if (!password.isBlank()) {
                    if (username.isBlank()) connection.command("AUTH", password);
                    else connection.command("AUTH", username, password);
                }
                connection.command("SELECT", Integer.toString(database));
                if (!"PONG".equals(connection.command("PING"))) throw new IOException("Redis PING failed");
                return connection;
            } catch (Exception error) {
                connection.close();
                if (error instanceof IOException io) throw io;
                throw new IOException("Redis initialization failed", error);
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

        @Override public void close() throws IOException { socket.close(); }

        private static String required(Properties properties, String key) {
            String value = properties.getProperty(key);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + key);
            return value.trim();
        }
        private static int integer(Properties properties, String key) {
            try { return Integer.parseInt(required(properties, key)); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("invalid " + key, error); }
        }
    }
}
