package com.cpgame.crazy777.server;

import com.cpgame.crazy777.generator.GameRuleCore;
import com.cpgame.crazy777.generator.MinimalRoundFactCodec;
import com.cpgame.crazy777.generator.RedisLoader;
import com.cpgame.crazy777.generator.ResultUtil;
import com.cpgame.crazy777.generator.RoundFactory;
import com.cpgame.crazy777.generator.RoundVerifier;
import com.cpgame.crazy777.generator.model.RoundFacts;
import com.cpgame.crazy777.generator.model.RoundMode;
import com.cpgame.crazy777.generator.model.RoundResult;

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

/** Controller v3 唯一结果源：结果类别 -> 已有整数倍率 -> LINDEX 一整个 ASCII member。 */
public class RedisRoundStore {
    private final AppConfig config;
    private final SecureRandom random = new SecureRandom();
    private final MinimalRoundFactCodec codec = new MinimalRoundFactCodec(new RoundFactory(), new RoundVerifier());
    private final GameRuleCore core = GameRuleCore.forRestoration();

    public RedisRoundStore(AppConfig config) { this.config = config; }
    RedisRoundStore() { this.config = null; }

    public RoundResult claim(BigDecimal bs, int bl, BigDecimal startingBalance) {
        Outcome outcome = chooseOutcome();
        try (Connection redis = Connection.connect(config)) {
            int ratio = switch (outcome) {
                case LOSS -> requireNonEmpty(redis, false, List.of(0));
                case WIN -> requireNonEmpty(redis, false, positiveRatios(redis, false));
                case SPECIAL -> requireNonEmpty(redis, true, positiveRatios(redis, true));
            };
            String key = outcome == Outcome.SPECIAL ? RedisLoader.specialList(config.redisGameId(), ratio)
                    : RedisLoader.normalList(config.redisGameId(), ratio);
            Object length = redis.command("LLEN", key);
            long len = length instanceof Long n ? n : Long.parseLong(String.valueOf(length));
            if (len <= 0) throw new PoolUnavailableException("REDIS_POOL_EMPTY", key);
            int offset = random.nextInt((int) Math.min(len, Integer.MAX_VALUE));
            Object raw = redis.command("LINDEX", key, Integer.toString(offset));
            if (!(raw instanceof String payload)) throw new PoolUnavailableException("REDIS_POOL_EMPTY", key);
            String roundKey = UUID.randomUUID().toString().replace("-", "");
            RoundResult base = codec.decodeRedisMember(payload, roundKey, bs, bl, startingBalance);
            RoundMode actual = ResultUtil.analyze(base).mode();
            if ((outcome == Outcome.LOSS && actual != RoundMode.ORDINARY_LOSS)
                    || (outcome == Outcome.WIN && actual != RoundMode.ORDINARY_WIN)
                    || (outcome == Outcome.SPECIAL && actual != RoundMode.FREE_SPINS)) {
                throw new PoolUnavailableException("REDIS_MEMBER_CATEGORY_MISMATCH", key);
            }
            RoundFacts projected = new RoundFacts(base.roundKey(), bs, bl, base.boards());
            RoundResult round = core.restore(projected, startingBalance);
            int projectedRatio = ResultUtil.analyze(round).totalMultiplier().intValueExact();
            if (projectedRatio != ratio) throw new PoolUnavailableException("REDIS_MEMBER_RATIO_MISMATCH", key);
            return round;
        } catch (PoolUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new PoolUnavailableException("REDIS_UNAVAILABLE",
                    config.redisHost() + ":" + config.redisPort() + " " + ex.getMessage());
        }
    }

    private Outcome chooseOutcome() {
        int total = Math.addExact(config.lossWeight(), Math.addExact(config.winWeight(), config.specialWeight()));
        int point = random.nextInt(total);
        if (point < config.lossWeight()) return Outcome.LOSS;
        point -= config.lossWeight();
        return point < config.winWeight() ? Outcome.WIN : Outcome.SPECIAL;
    }

    private List<Integer> positiveRatios(Connection redis, boolean special) throws IOException {
        String index = special ? RedisLoader.specialIndex(config.redisGameId()) : RedisLoader.normalIndex(config.redisGameId());
        Object value = redis.command("ZRANGE", index, "0", "-1");
        List<Integer> result = new ArrayList<>();
        if (value instanceof List<?> list) for (Object item : list) {
            int ratio = Integer.parseInt(item.toString());
            if (ratio > 0) result.add(ratio);
        }
        return result;
    }

    private int requireNonEmpty(Connection redis, boolean special, List<Integer> ratios) throws IOException {
        List<Integer> available = new ArrayList<>();
        for (int ratio : ratios) {
            String key = special ? RedisLoader.specialList(config.redisGameId(), ratio)
                    : RedisLoader.normalList(config.redisGameId(), ratio);
            Object length = redis.command("LLEN", key);
            if (length instanceof Long n && n > 0) available.add(ratio);
        }
        if (available.isEmpty()) {
            throw new PoolUnavailableException("REDIS_POOL_EMPTY",
                    special ? RedisLoader.specialIndex(config.redisGameId()) : RedisLoader.normalIndex(config.redisGameId()));
        }
        return available.get(random.nextInt(available.size()));
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
