package com.cpgame.crazypiggy.server;

import com.cpgame.demo.redis.RedisFloorLookup;

import com.cpgame.crazypiggy.generator.GameRuleCore;
import com.cpgame.crazypiggy.generator.MinimalRoundFactCodec;
import com.cpgame.crazypiggy.generator.RedisLoader;
import com.cpgame.crazypiggy.generator.ResultUtil;
import com.cpgame.crazypiggy.generator.RoundFactory;
import com.cpgame.crazypiggy.generator.RoundVerifier;
import com.cpgame.crazypiggy.generator.model.RoundFacts;
import com.cpgame.crazypiggy.generator.model.RoundMode;
import com.cpgame.crazypiggy.generator.model.RoundResult;

import java.io.*;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Controller v3 唯一结果源：结果类别 -> 已有整数倍率 -> LINDEX 一整个 ASCII member。 */
public class RedisRoundStore {
    private final AppConfig config;
    private final AtomicLong outcomeCursor = new AtomicLong();
    private final java.security.SecureRandom random = new java.security.SecureRandom();
    private final MinimalRoundFactCodec codec = new MinimalRoundFactCodec(new RoundFactory(), new RoundVerifier());
    private final GameRuleCore core = GameRuleCore.forRestoration();
    private final GameRuleCore losses = new GameRuleCore();

    public RedisRoundStore(AppConfig config) { this.config = config; }
    RedisRoundStore() { this.config = null; }

    public RoundResult claim(BigDecimal bs, int bl) {
        if (chooseOutcome() == Outcome.LOSS) return losses.generateIndependentLoss(bs, bl);
        try (Connection redis = Connection.connect(config)) {
            return take(redis, bs, bl);
        } catch (PoolUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new PoolUnavailableException("REDIS_UNAVAILABLE", ex.getMessage());
        }
    }

    private RoundResult take(Connection redis, BigDecimal bs, int bl) throws IOException {
        int ratio = requireNonEmpty(redis, 1, Integer.MAX_VALUE);
        String key = RedisLoader.normalList(config.redisGameId(), ratio);
        Object length = redis.command("LLEN", key);
        long len = length instanceof Long n ? n : Long.parseLong(String.valueOf(length));
        if (len <= 0) throw new PoolUnavailableException("REDIS_POOL_EMPTY", key);
        long offset = random.nextLong(len);
        Object raw = redis.command("LINDEX", key, Long.toString(offset));
        if (!(raw instanceof String payload)) throw new PoolUnavailableException("REDIS_POOL_EMPTY", key);
        RoundResult base = codec.decodeRedisMember(payload);
        RoundMode actual = ResultUtil.analyze(base).mode();
        if (actual != RoundMode.ORDINARY_WIN && actual != RoundMode.BOOSTER_WHEEL)
            throw new PoolUnavailableException("REDIS_MEMBER_CATEGORY_MISMATCH", key);
        RoundFacts projected = new RoundFacts(base.roundKey(), base.createdAtEpochSecond(), bs, bl,
                base.symbols(), base.wheelPositions(), base.wheelMultipliers());
        RoundResult round = core.restore(projected);
        int projectedRatio = ResultUtil.analyze(round).totalAward()
                .divide(ResultUtil.analyze(round).betAmount()).intValueExact();
        if (projectedRatio != ratio) throw new PoolUnavailableException("REDIS_MEMBER_RATIO_MISMATCH", key);
        return round;
    }

    private Outcome chooseOutcome() {
        int total = Math.addExact(config.lossWeight(), Math.addExact(config.winWeight(), config.specialWeight()));
        int point = (int) Math.floorMod(outcomeCursor.getAndIncrement() * 7_919L, total);
        if (point < config.lossWeight()) return Outcome.LOSS;
        return Outcome.WIN;
    }

    private int requireNonEmpty(Connection redis, int minimum, int maximum) throws IOException {
        String index = RedisLoader.normalIndex(config.redisGameId());
        Integer selected = RedisFloorLookup.choose(redis::command, index,
                m -> RedisLoader.normalList(config.redisGameId(), m),
                random, minimum, maximum);
        if (selected == null) throw new PoolUnavailableException("REDIS_POOL_EMPTY", index);
        return selected;
    }

    private enum Outcome { LOSS, WIN }

    public static final class PoolUnavailableException extends RuntimeException {
        private final String code;
        PoolUnavailableException(String code, String detail) { super(detail == null ? "" : detail); this.code = code; }
        public String code() { return code; }
    }

    private static final class Connection implements AutoCloseable {
        private final Socket socket; private final InputStream in; private final OutputStream out;
        private Connection(Socket socket) throws IOException {
            this.socket=socket; in=new BufferedInputStream(socket.getInputStream()); out=new BufferedOutputStream(socket.getOutputStream());
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
            if (c.redisDatabase()!=0) r.command("SELECT", Integer.toString(c.redisDatabase()));
            if (!"PONG".equals(r.command("PING"))) throw new IOException("Redis PING failed");
            return r;
        }
        Object command(String... args) throws IOException { write(args); out.flush(); return read(); }
        private void write(String[] args) throws IOException {
            out.write(("*"+args.length+"\r\n").getBytes(StandardCharsets.US_ASCII));
            for(String arg:args){byte[] b=arg.getBytes(StandardCharsets.UTF_8);out.write(("$"+b.length+"\r\n").getBytes(StandardCharsets.US_ASCII));out.write(b);out.write('\r');out.write('\n');}
        }
        private Object read() throws IOException {
            int p=in.read(); if(p<0)throw new EOFException();
            return switch(p){case '+'->line();case '-'->throw new IOException("Redis: "+line());case ':'->Long.parseLong(line());case '$'->bulk();case '*'->array();default->throw new IOException("bad RESP");};
        }
        private String line() throws IOException {ByteArrayOutputStream b=new ByteArrayOutputStream();int prev=-1;while(true){int cur=in.read();if(cur<0)throw new EOFException();if(prev=='\r'&&cur=='\n')break;if(prev>=0)b.write(prev);prev=cur;}return b.toString(StandardCharsets.UTF_8);}
        private Object bulk() throws IOException {int n=Integer.parseInt(line());if(n<0)return null;byte[] b=in.readNBytes(n);if(b.length!=n||in.read()!='\r'||in.read()!='\n')throw new EOFException();return new String(b,StandardCharsets.UTF_8);}
        private Object array() throws IOException {int n=Integer.parseInt(line());if(n<0)return null;List<Object> a=new ArrayList<>();for(int i=0;i<n;i++)a.add(read());return a;}
        public void close() throws IOException {socket.close();}
    }
}
