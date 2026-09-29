package com.cpgame.fishinggo.loader;

import com.cpgame.fishinggo.core.CompleteRound;
import com.cpgame.fishinggo.core.DealingWeights;
import com.cpgame.fishinggo.core.ProtocolConstants;
import com.cpgame.fishinggo.core.RedisKeys;
import com.cpgame.fishinggo.core.ResultUtil;
import com.cpgame.fishinggo.core.RoundCodec;
import com.cpgame.fishinggo.core.RoundGenerator;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;

import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Properties;
import java.util.TreeMap;

public final class RedisLoader {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: java -jar fishing-go-loader.jar generator.properties");
        Properties p = new Properties();
        try (Reader r = new InputStreamReader(new FileInputStream(args[0]), StandardCharsets.UTF_8)) { p.load(r); LoaderLimits.checkKeys(p); }
        String host = req(p, "redis.host");
        int port = num(p, "redis.port", 1, 65535), db = num(p, "redis.database", 0, 15);

        int loss = num(p, "generation.loss-count", 0, Integer.MAX_VALUE);
        int win = num(p, "generation.win-count", 0, Integer.MAX_VALUE);
        int special = num(p, "generation.special-count", 0, Integer.MAX_VALUE);
        int cap = num(p, "generation.max-members-per-multiplier", 1, 1_000_000);
        DefaultJedisClientConfig.Builder cfg = DefaultJedisClientConfig.builder().database(db)
                .connectionTimeoutMillis(num(p, "redis.connect-timeout-ms", 1, 120000))
                .socketTimeoutMillis(num(p, "redis.socket-timeout-ms", 1, 120000))
                .ssl(Boolean.parseBoolean(p.getProperty("redis.ssl", "false")));
        String username=p.getProperty("redis.username", "").trim(), password=p.getProperty("redis.password", "");
        if (!username.isEmpty()) cfg.user(username);
        if (!password.isEmpty()) cfg.password(password);
        if (!"8000054".equals(req(p, "redis.game-id"))) throw new IllegalArgumentException("redis.game-id must be positive");
        DealingWeights weights = weights(p);
        RoundGenerator generator = new RoundGenerator(new SecureRandom(), weights);
        ResultUtil util = new ResultUtil();
        RoundCodec codec = new RoundCodec();
        Map<String, Integer> counts = new TreeMap<>();
        LoaderLimits limits = new LoaderLimits(p);
        loss = limits.lossTarget(loss);
        int lossGoal = loss, winGoal = win, specialGoal = special;
        long[] attempts = {0};
        long scTotal = weights.entryScatter().values().stream().mapToLong(Integer::longValue).sum();
        int sc5 = (int) ((long) special * weights.entryScatter().get(5) / scTotal);
        int sc6 = (int) ((long) special * weights.entryScatter().get(6) / scTotal), sc7 = special - sc5 - sc6;
        try (Jedis jedis = new Jedis(host, port, cfg.build())) {
            jedis.connect();
            if (!"PONG".equalsIgnoreCase(jedis.ping())) throw new IllegalStateException("redis ping failed");
            while (loss > 0 || win > 0 || sc5 > 0 || sc6 > 0 || sc7 > 0) {
                if (win > 0 && write(jedis, generator.win(), util, codec, cap, counts, limits, attempts)) win--;
                if (sc5 > 0 && write(jedis, generator.special(5), util, codec, cap, counts, limits, attempts)) sc5--;
                if (sc6 > 0 && write(jedis, generator.special(6), util, codec, cap, counts, limits, attempts)) sc6--;
                if (sc7 > 0 && write(jedis, generator.special(7), util, codec, cap, counts, limits, attempts)) sc7--;
                if (loss > 0 && write(jedis, generator.loss(), util, codec, cap, counts, limits, attempts)) loss--;
            }
            System.out.printf(Locale.ROOT, "Loaded Fishing GO loss=%d win=%d special=%d rulesHash=%s buckets=%s%n",
                    lossGoal, winGoal, specialGoal, ProtocolConstants.RULES_HASH, counts);
        }
    }

    private static boolean write(Jedis jedis, CompleteRound round, ResultUtil util, RoundCodec codec,
                              int cap, Map<String, Integer> counts, LoaderLimits limits, long[] attempts) {
        LoaderLimits.checkAttempts(++attempts[0], 100);
        ResultUtil.Analysis a = util.analyze(round);
        boolean specialPool = RedisKeys.index(a.outcome()).startsWith("MaryKeyList_");
        if (!limits.accepts(specialPool, a.odds())) return false;
        String member = codec.encode(round);
        if (!member.equals(codec.encode(codec.decode(member, new RoundGenerator(new java.security.SecureRandom())))))
            throw new IllegalStateException("codec roundtrip");
        if (!member.chars().allMatch(c -> c >= 32 && c <= 126)) throw new IllegalStateException("member not ASCII");
        if (member.startsWith("{") || member.startsWith("[")) throw new IllegalStateException("json member");
        String list = RedisKeys.list(a.outcome(), a.odds());
        String index = RedisKeys.index(a.outcome());
        Transaction tx = jedis.multi();
        tx.zadd(index, (double) a.odds(), Integer.toString(a.odds()));
        tx.rpush(list, member);
        tx.ltrim(list, -(specialPool ? limits.specialCap : cap), -1);
        tx.exec();
        counts.merge(list, 1, Integer::sum);
        return true;
    }

    private static String req(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("missing " + key);
        return v.trim();
    }
    private static int num(Properties p, String key, int min, int max) {
        int v = Integer.parseInt(req(p, key));
        if (v < min || v > max) throw new IllegalArgumentException(key);
        return v;
    }

    private static DealingWeights weights(Properties p) {
        return new DealingWeights(symbols(p, "paid", ProtocolConstants.ORDER),
                symbols(p, "free", ProtocolConstants.ORDER),
                symbols(p, "entry", DealingWeights.FILL),
                Map.of(5, num(p, "generation.entry-scatter.5-weight", 1, Integer.MAX_VALUE),
                        6, num(p, "generation.entry-scatter.6-weight", 1, Integer.MAX_VALUE),
                        7, num(p, "generation.entry-scatter.7-weight", 1, Integer.MAX_VALUE)));
    }

    private static Map<String, Integer> symbols(Properties p, String mode, java.util.List<String> symbols) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String symbol : symbols)
            result.put(symbol, num(p, "generation.symbol." + symbol + "." + mode + "-weight", 1, Integer.MAX_VALUE));
        return result;
    }
}
