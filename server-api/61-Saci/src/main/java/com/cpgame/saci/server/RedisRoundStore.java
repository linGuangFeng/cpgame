package com.cpgame.saci.server;

import com.cpgame.saci.generator.GameRuleCore;
import com.cpgame.saci.generator.MinimalRoundFactCodec;
import com.cpgame.saci.generator.RedisLoader;
import com.cpgame.saci.generator.ResultUtil;
import com.cpgame.saci.generator.RoundFactory;
import com.cpgame.saci.generator.RoundVerifier;
import com.cpgame.saci.generator.model.RoundCandidate;
import com.cpgame.saci.generator.model.RoundMode;
import com.cpgame.saci.generator.model.RoundResult;
import com.cpgame.saci.generator.model.StepFact;

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

public class RedisRoundStore {
    private final AppConfig config;
    private final SecureRandom random = new SecureRandom();
    private final MinimalRoundFactCodec codec = new MinimalRoundFactCodec(new RoundFactory(), new RoundVerifier());
    private final GameRuleCore core = GameRuleCore.forRestoration();

    public RedisRoundStore(AppConfig config) { this.config = config; }
    RedisRoundStore() { this.config = null; }

    public RoundResult claim(BigDecimal bs, int bl, BigDecimal startingBalance) {
        return claim(bs, bl, startingBalance, ResultUtil.EnergyState.initial());
    }

    public RoundResult claim(BigDecimal bs, int bl, BigDecimal startingBalance, ResultUtil.EnergyState energy) {
        Outcome outcome = chooseOutcome();
        try {
            return new RuntimeRoundComposer(this, core).composePaid(bs, bl, startingBalance, energy, outcome);
        } catch (PoolUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new PoolUnavailableException("REDIS_UNAVAILABLE",
                    config.redisHost() + ":" + config.redisPort() + " " + ex.getMessage());
        }
    }

    Outcome chooseOutcome() {
        int total = Math.addExact(config.lossWeight(), Math.addExact(config.winWeight(), config.specialWeight()));
        int point = random.nextInt(total);
        if (point < config.lossWeight()) return Outcome.LOSS;
        point -= config.lossWeight();
        return point < config.winWeight() ? Outcome.WIN : Outcome.FREE;
    }

    RoundCandidate claimCandidate(boolean special, RoundMode required) {
        try (Connection redis = Connection.connect(config)) {
            List<Integer> ratios = special ? positiveRatios(redis, true)
                    : required == RoundMode.ORDINARY_LOSS ? List.of(0) : positiveRatios(redis, false);
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
            while (!available.isEmpty()) {
                int ratio = available.remove(random.nextInt(available.size()));
                String key = special ? RedisLoader.specialList(config.redisGameId(), ratio)
                        : RedisLoader.normalList(config.redisGameId(), ratio);
                Object length = redis.command("LLEN", key);
                long len = length instanceof Long n ? n : Long.parseLong(String.valueOf(length));
                List<Integer> offsets = new ArrayList<>();
                for (int i = 0; i < len && i < Integer.MAX_VALUE; i++) offsets.add(i);
                while (!offsets.isEmpty()) {
                    int offset = offsets.remove(random.nextInt(offsets.size()));
                    Object raw = redis.command("LINDEX", key, Integer.toString(offset));
                    if (!(raw instanceof String payload)) continue;
                    try {
                        List<StepFact> steps = codec.decodeSteps(payload);
                        if (MinimalRoundFactCodec.infer(steps) != required) continue;
                        if (required == RoundMode.ORDINARY_LOSS || required == RoundMode.ORDINARY_WIN) {
                            if (!ResultUtil.canApplyEnergyTransition(ResultUtil.EnergyState.initial(), steps)) continue;
                        }
                        return new RoundCandidate(required, steps);
                    } catch (RuntimeException ignored) {
                        // 非本入口 member 留在原池
                    }
                }
            }
            throw new PoolUnavailableException("REDIS_POOL_EMPTY", "mode=" + required);
        } catch (PoolUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new PoolUnavailableException("REDIS_UNAVAILABLE",
                    config.redisHost() + ":" + config.redisPort() + " " + ex.getMessage());
        }
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

    enum Outcome { LOSS, WIN, FREE }

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
