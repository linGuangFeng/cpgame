package com.hd.cpgame.magicscroll2.server;

import com.cpgame.demo.redis.RedisFloorLookup;

import com.hd.cpgame.magicscroll2.core.GameRuleCore;
import com.hd.cpgame.magicscroll2.core.GeneratedRound;
import com.hd.cpgame.magicscroll2.core.GenerationPolicy;
import com.hd.cpgame.magicscroll2.core.RedisMemberCodec;
import com.hd.cpgame.magicscroll2.core.ResultUtil;
import com.hd.cpgame.magicscroll2.core.RoundMode;
import com.hd.cpgame.magicscroll2.core.SymbolWeightPolicy;
import com.hd.cpgame.magicscroll2.core.TrialProbabilityPolicy;
import com.hd.cpgame.magicscroll2.loader.RedisLoader;

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

/** Controller v3 唯一结果源：类别 -> 已有整数倍率 -> LINDEX 一条 ASCII member。 */
public class RedisRoundStore {
    private final AppConfig config;
    private final SecureRandom random = new SecureRandom();
    private final RedisMemberCodec codec = new RedisMemberCodec();
    private final GameRuleCore core = new GameRuleCore(GenerationPolicy.defaults(),
            new TrialProbabilityPolicy(1, 1, 1, 1), SymbolWeightPolicy.localReplicaDefaults());

    public RedisRoundStore(AppConfig config) { this.config = config; }
    RedisRoundStore() { this.config = null; }

    public GeneratedRound claim(BigDecimal paidBet) {
        Outcome outcome = chooseOutcome();
        try (Connection redis = Connection.connect(config)) {
            int ratio = switch (outcome) {
                case LOSS -> requireNonEmpty(redis, false, 0, 0);
                case WIN -> requireNonEmpty(redis, false, 1, Integer.MAX_VALUE);
                case SPECIAL -> requireNonEmpty(redis, true, 1, Integer.MAX_VALUE);
            };
            String key = outcome == Outcome.SPECIAL ? RedisLoader.specialList(config.redisGameId, ratio)
                    : RedisLoader.normalList(config.redisGameId, ratio);
            Object length = redis.command("LLEN", key);
            long len = length instanceof Long n ? n : Long.parseLong(String.valueOf(length));
            if (len <= 0) throw new PoolUnavailableException("REDIS_POOL_EMPTY", key);
            int offset = random.nextInt((int) Math.min(len, Integer.MAX_VALUE));
            Object raw = redis.command("LINDEX", key, Integer.toString(offset));
            if (!(raw instanceof String payload)) throw new PoolUnavailableException("REDIS_POOL_EMPTY", key);
            GeneratedRound round = codec.decode(payload, paidBet);
            ResultUtil.RoundAnalysis actual = core.resultUtil()
                    .analyzeCompleteRound(round, GenerationPolicy.defaults());
            if ((outcome == Outcome.LOSS && actual.getMode() != RoundMode.LOSS)
                    || (outcome == Outcome.WIN && actual.getMode() != RoundMode.BASE_WIN)
                    || (outcome == Outcome.SPECIAL && !actual.isSpecial())) {
                throw new PoolUnavailableException("REDIS_MEMBER_CATEGORY_MISMATCH", key);
            }
            int projectedRatio = core.resultUtil().integerRatio(round, GenerationPolicy.defaults());
            if (projectedRatio != ratio) throw new PoolUnavailableException("REDIS_MEMBER_RATIO_MISMATCH", key);
            return round;
        } catch (PoolUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new PoolUnavailableException("REDIS_UNAVAILABLE",
                    config.redisHost + ":" + config.redisPort + " " + ex.getMessage());
        }
    }

    private Outcome chooseOutcome() {
        int total = Math.addExact(config.lossWeight, Math.addExact(config.winWeight, config.specialWeight));
        int point = random.nextInt(total);
        if (point < config.lossWeight) return Outcome.LOSS;
        point -= config.lossWeight;
        return point < config.winWeight ? Outcome.WIN : Outcome.SPECIAL;
    }

    

    private int requireNonEmpty(Connection redis, boolean special, int minimum, int maximum) throws IOException {
        String index = special ? RedisLoader.specialIndex(config.redisGameId) : RedisLoader.normalIndex(config.redisGameId);
        Integer selected = RedisFloorLookup.choose(redis::command, index,
                m -> special ? RedisLoader.specialList(config.redisGameId, m) : RedisLoader.normalList(config.redisGameId, m),
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
            s.connect(new InetSocketAddress(c.redisHost, c.redisPort), c.redisConnectTimeoutMs);
            s.setSoTimeout(c.redisSocketTimeoutMs);
            Connection r = new Connection(s);
            if (!c.redisPassword.isEmpty()) {
                if (c.redisUsername.isEmpty()) r.command("AUTH", c.redisPassword);
                else r.command("AUTH", c.redisUsername, c.redisPassword);
            }
            if (c.redisDatabase != 0) r.command("SELECT", Integer.toString(c.redisDatabase));
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
            int prefix = in.read();
            if (prefix < 0) throw new EOFException("Redis 已关闭连接");
            return switch (prefix) {
                case '+' -> line();
                case '-' -> throw new IOException("Redis 错误: " + line());
                case ':' -> Long.parseLong(line());
                case '$' -> bulk();
                case '*' -> array();
                default -> throw new IOException("非法 RESP 前缀: " + (char) prefix);
            };
        }
        private String line() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int previous = -1;
            while (true) {
                int current = in.read();
                if (current < 0) throw new EOFException();
                if (previous == '\r' && current == '\n') break;
                if (previous >= 0) bytes.write(previous);
                previous = current;
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
        private Object bulk() throws IOException {
            int length = Integer.parseInt(line());
            if (length < 0) return null;
            byte[] value = new byte[length];
            int offset = 0;
            while (offset < length) {
                int count = in.read(value, offset, length - offset);
                if (count < 0) throw new EOFException();
                offset += count;
            }
            if (in.read() != '\r' || in.read() != '\n') throw new EOFException();
            return new String(value, StandardCharsets.UTF_8);
        }
        private Object array() throws IOException {
            int length = Integer.parseInt(line());
            if (length < 0) return null;
            List<Object> result = new ArrayList<>(length);
            for (int i = 0; i < length; i++) result.add(read());
            return result;
        }
        @Override public void close() throws IOException { socket.close(); }
    }
}
