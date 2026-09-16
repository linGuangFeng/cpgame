package com.cpgame.luckycatii.server;

import com.cpgame.demo.redis.RedisFloorLookup;

import com.cpgame.luckycatii.GameRuleCore;
import com.cpgame.luckycatii.GameRules;
import com.cpgame.luckycatii.MinimalRoundFactCodec;
import com.cpgame.luckycatii.RedisLoader;
import com.cpgame.luckycatii.ResultUtil;
import com.cpgame.luckycatii.RoundFactory;
import com.cpgame.luckycatii.RoundVerifier;
import com.cpgame.luckycatii.model.ResultAnalysis;
import com.cpgame.luckycatii.model.RoundFacts;
import com.cpgame.luckycatii.model.RoundMode;
import com.cpgame.luckycatii.model.RoundResult;

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

/** Demo results come only from Redis complete-round members. */
public final class RedisRoundStore {
    private final AppConfig config;
    private final SecureRandom random = new SecureRandom();
    private final MinimalRoundFactCodec codec = new MinimalRoundFactCodec(new RoundFactory(), new RoundVerifier());
    private final GameRuleCore core = GameRuleCore.forRestoration();

    public RedisRoundStore(AppConfig config) { this.config = config; }

    public RoundResult claim(BigDecimal bs, int bl) {
        if (!GameRules.legalBet(bs, bl)) throw new IllegalArgumentException("非法下注档位");
        Outcome outcome = chooseOutcome();
        try (Connection redis = Connection.connect(config)) {
            int ratio = switch (outcome) {
                case LOSS -> requireNonEmpty(redis, false, 0, 0);
                case WIN -> requireNonEmpty(redis, false, 1, Integer.MAX_VALUE);
                case SPECIAL -> requireNonEmpty(redis, true, 0, Integer.MAX_VALUE);
            };
            String key = outcome == Outcome.SPECIAL ? RedisLoader.specialList(config.redisGameId(), ratio)
                    : RedisLoader.normalList(config.redisGameId(), ratio);
            Object length = redis.command("LLEN", key);
            long len = length instanceof Long n ? n : Long.parseLong(String.valueOf(length));
            if (len <= 0) throw new PoolUnavailableException("REDIS_POOL_EMPTY", key);
            int offset = random.nextInt((int) Math.min(len, Integer.MAX_VALUE));
            Object raw = redis.command("LINDEX", key, Integer.toString(offset));
            if (!(raw instanceof String payload) || payload.isEmpty())
                throw new PoolUnavailableException("REDIS_POOL_EMPTY", key);
            if (payload.charAt(0) == '{' || payload.charAt(0) == '[')
                throw new PoolUnavailableException("REDIS_MEMBER_NOT_ASCII", key);
            RoundResult base = codec.decodeRedisMember(payload);
            ResultAnalysis actual = ResultUtil.analyze(base);
            if ((outcome == Outcome.LOSS && actual.redisPoolMode() != RoundMode.ORDINARY_LOSS)
                    || (outcome == Outcome.WIN && actual.redisPoolMode() != RoundMode.ORDINARY_WIN)
                    || (outcome == Outcome.SPECIAL && !ResultUtil.isSpecialPool(actual)))
                throw new PoolUnavailableException("REDIS_MEMBER_CATEGORY_MISMATCH", key);
            RoundFacts projected = new RoundFacts(base.roundKey(), base.createdAtEpochSecond(), bs, bl,
                    base.paidBoard(), base.finalBoard(), base.rpx(), base.gameMode() == 1);
            RoundResult round = core.restore(projected);
            if (ResultUtil.analyze(round).integerMultiplier() != ratio)
                throw new PoolUnavailableException("REDIS_MEMBER_RATIO_MISMATCH", key);
            return round;
        } catch (PoolUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new PoolUnavailableException("REDIS_UNAVAILABLE", ex.getMessage());
        }
    }

    private Outcome chooseOutcome() {
        int total = Math.addExact(config.lossWeight(), Math.addExact(config.winWeight(), config.specialWeight()));
        int point = random.nextInt(total);
        if (point < config.lossWeight()) return Outcome.LOSS;
        point -= config.lossWeight();
        return point < config.winWeight() ? Outcome.WIN : Outcome.SPECIAL;
    }

    

    

    private int requireNonEmpty(Connection redis, boolean special, int minimum, int maximum) throws IOException {
        String index = special ? RedisLoader.specialIndex(config.redisGameId()) : RedisLoader.normalIndex(config.redisGameId());
        Integer selected = RedisFloorLookup.choose(redis::command, index,
                m -> special ? RedisLoader.specialList(config.redisGameId(), m) : RedisLoader.normalList(config.redisGameId(), m),
                random, minimum, maximum);
        if (selected == null) throw new PoolUnavailableException("REDIS_POOL_EMPTY", index);
        return selected;
    }

    private enum Outcome { LOSS, WIN, SPECIAL }

    public static final class PoolUnavailableException extends RuntimeException {
        private final String code;
        PoolUnavailableException(String code, String detail) {
            super(detail == null ? "" : detail);
            this.code = code;
        }
        public String code() { return code; }
    }

    static final class Connection implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private Connection(Socket socket) throws IOException {
            this.socket = socket;
            in = new BufferedInputStream(socket.getInputStream());
            out = new BufferedOutputStream(socket.getOutputStream());
        }
        static Connection connect(AppConfig c) throws IOException {
            Socket s = new Socket();
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
