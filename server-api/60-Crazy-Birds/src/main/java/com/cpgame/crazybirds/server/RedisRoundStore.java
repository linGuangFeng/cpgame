package com.cpgame.crazybirds.server;

import com.cpgame.demo.redis.RedisFloorLookup;

import com.cpgame.crazybirds.generator.GameRules;
import com.cpgame.crazybirds.generator.MinimalRoundFactCodec;
import com.cpgame.crazybirds.generator.RedisLoader;
import com.cpgame.crazybirds.generator.RoundFactory;
import com.cpgame.crazybirds.generator.RoundVerifier;
import com.cpgame.crazybirds.generator.model.ResultAnalysis;
import com.cpgame.crazybirds.generator.model.RoundMode;
import com.cpgame.crazybirds.generator.model.RoundResult;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.net.ssl.SSLSocketFactory;

/** Controller v4 唯一结果源：场景 -> 倍率段 -> 已有整数倍率 -> LINDEX 一整个 ASCII member。 */
public class RedisRoundStore {
    private final AppConfig config;
    private final SecureRandom random = new SecureRandom();
    private final RoundVerifier verifier = new RoundVerifier();
    private final MinimalRoundFactCodec codec = new MinimalRoundFactCodec(new RoundFactory(), verifier);

    public RedisRoundStore(AppConfig config) { this.config = config; }
    RedisRoundStore() { this.config = null; }

    public RoundResult claim(BigDecimal bs, int bl, BigDecimal startingBalance) {
        boolean freeSpins = config.chooseFreeSpins(random);
        MultiplierBand band = (freeSpins ? config.freeSpinsBands() : config.normalBands()).choose(random);
        int target = band.target(random);
        boolean mary = freeSpins;
        int type = mary ? GameRules.FREE_SPINS_MARY_POOL_TYPE : GameRules.NORMAL_POOL_TYPE;
        try (Connection redis = Connection.connect(config)) {
            int ratio = requireNonEmpty(redis, mary, type, band, target);
            String key = mary
                    ? RedisLoader.maryList(config.redisGameId(), type, ratio)
                    : RedisLoader.normalList(config.redisGameId(), type, ratio);
            Object length = redis.command("LLEN", key);
            long len = length instanceof Long n ? n : Long.parseLong(String.valueOf(length));
            if (len <= 0) throw new PoolUnavailableException("REDIS_POOL_EMPTY", key);
            int offset = random.nextInt((int) Math.min(len, Integer.MAX_VALUE));
            Object raw = redis.command("LINDEX", key, Integer.toString(offset));
            if (!(raw instanceof String payload)) throw new PoolUnavailableException("REDIS_POOL_EMPTY", key);
            String roundKey = UUID.randomUUID().toString().replace("-", "");
            RoundResult round = codec.decodeRedisMember(payload, roundKey, bs, bl, startingBalance);
            ResultAnalysis actual = verifier.verify(round);
            if ((freeSpins && actual.mode() != RoundMode.FREE_SPINS)
                    || (!freeSpins && actual.mode() == RoundMode.FREE_SPINS)) {
                throw new PoolUnavailableException("REDIS_MEMBER_CATEGORY_MISMATCH", key);
            }
            int projectedRatio = GameRules.cacheMultiplier(actual.totalMultiplier());
            if (projectedRatio != ratio) throw new PoolUnavailableException("REDIS_MEMBER_RATIO_MISMATCH", key);
            return round;
        } catch (PoolUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new PoolUnavailableException("REDIS_UNAVAILABLE",
                    config.redisHost() + ":" + config.redisPort() + " " + ex.getMessage());
        }
    }

    private int requireNonEmpty(Connection redis, boolean mary, int type,
                                MultiplierBand band, int target) throws IOException {
        String index = mary
                ? RedisLoader.maryIndex(config.redisGameId(), type)
                : RedisLoader.normalIndex(config.redisGameId(), type);
        Integer selected = RedisFloorLookup.floor(redis::command, index,
                m -> mary
                        ? RedisLoader.maryList(config.redisGameId(), type, m)
                        : RedisLoader.normalList(config.redisGameId(), type, m),
                target, band.minimum());
        if (selected == null) throw new PoolUnavailableException("REDIS_POOL_EMPTY", index);
        return selected;
    }

    public static final class PoolUnavailableException extends RuntimeException {
        private final String code;
        PoolUnavailableException(String code, String detail) {
            super(detail == null ? "" : detail);
            this.code = code;
        }
        public String code() { return code; }
    }

    private static final class Connection implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private Connection(Socket socket) throws IOException {
            this.socket = socket;
            in = new BufferedInputStream(socket.getInputStream());
            out = new BufferedOutputStream(socket.getOutputStream());
        }
        static Connection connect(AppConfig c) throws IOException {
            Socket s = c.redisSsl() ? SSLSocketFactory.getDefault().createSocket() : new Socket();
            s.connect(new InetSocketAddress(c.redisHost(), c.redisPort()), c.redisConnectTimeoutMs());
            s.setSoTimeout(c.redisSocketTimeoutMs());
            Connection r = new Connection(s);
            if (!c.redisPassword().isEmpty()) {
                if (c.redisUsername().isEmpty()) r.command("AUTH", c.redisPassword());
                else r.command("AUTH", c.redisUsername(), c.redisPassword());
            }
            if (c.redisDatabase() != 0) r.command("SELECT", Integer.toString(c.redisDatabase()));
            if (!"PONG".equals(r.command("PING"))) throw new IOException("Redis PING failed");
            return r;
        }
        Object command(String... args) throws IOException { write(args); out.flush(); return read(); }
        private void write(String[] args) throws IOException {
            out.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            for (String arg : args) {
                byte[] b = arg.getBytes(StandardCharsets.UTF_8);
                out.write(("$" + b.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(b);
                out.write('\r');
                out.write('\n');
            }
        }
        private Object read() throws IOException {
            int p = in.read();
            if (p < 0) throw new EOFException();
            return switch (p) {
                case '+' -> line();
                case '-' -> throw new IOException("Redis: " + line());
                case ':' -> Long.parseLong(line());
                case '$' -> bulk();
                case '*' -> array();
                default -> throw new IOException("bad RESP");
            };
        }
        private String line() throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int prev = -1;
            while (true) {
                int cur = in.read();
                if (cur < 0) throw new EOFException();
                if (prev == '\r' && cur == '\n') break;
                if (prev >= 0) b.write(prev);
                prev = cur;
            }
            return b.toString(StandardCharsets.UTF_8);
        }
        private Object bulk() throws IOException {
            int n = Integer.parseInt(line());
            if (n < 0) return null;
            byte[] b = in.readNBytes(n);
            if (b.length != n || in.read() != '\r' || in.read() != '\n') throw new EOFException();
            return new String(b, StandardCharsets.UTF_8);
        }
        private Object array() throws IOException {
            int n = Integer.parseInt(line());
            if (n < 0) return null;
            List<Object> a = new ArrayList<>();
            for (int i = 0; i < n; i++) a.add(read());
            return a;
        }
        @Override public void close() throws IOException { socket.close(); }
    }
}
