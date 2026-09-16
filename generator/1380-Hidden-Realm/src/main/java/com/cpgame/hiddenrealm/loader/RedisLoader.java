package com.cpgame.hiddenrealm.loader;

import com.cpgame.hiddenrealm.core.CompleteRound;
import com.cpgame.hiddenrealm.core.GameRuleCore;
import com.cpgame.hiddenrealm.core.RedisKeys;
import com.cpgame.hiddenrealm.core.ResultUtil;
import com.cpgame.hiddenrealm.core.RoundCodec;
import com.cpgame.hiddenrealm.core.RoundGenerator;
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
import java.util.Properties;
import java.util.TreeMap;

public final class RedisLoader {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: java -jar hidden-realm-loader.jar generator.properties");
        Properties p = new Properties();
        try (Reader r = new InputStreamReader(new FileInputStream(args[0]), StandardCharsets.UTF_8)) { p.load(r); LoaderLimits.checkKeys(p); }
        String host = req(p, "redis.host"), game = req(p, "redis.game-id");
        int port = num(p, "redis.port", 1, 65535), db = num(p, "redis.database", 0, 15);
        int loss = num(p, "generation.loss-count", 0, Integer.MAX_VALUE);
        int win = num(p, "generation.win-count", 0, Integer.MAX_VALUE);
        int special = num(p, "generation.special-count", 0, Integer.MAX_VALUE);
        int cap = num(p, "generation.max-members-per-multiplier", 1, 1_000_000);

        RedisKeys.prefix(game);
        DefaultJedisClientConfig.Builder cfg = DefaultJedisClientConfig.builder().database(db)
                .connectionTimeoutMillis(num(p, "redis.connect-timeout-ms", 1, 120000))
                .socketTimeoutMillis(num(p, "redis.socket-timeout-ms", 1, 120000))
                .ssl(Boolean.parseBoolean(p.getProperty("redis.ssl", "false")));
        String user = p.getProperty("redis.username", "").trim(), pass = p.getProperty("redis.password", "").trim();
        if (!user.isEmpty()) cfg.user(user);
        if (!pass.isEmpty()) cfg.password(pass);
        GameRuleCore rules = new GameRuleCore();
        RoundGenerator generator = new RoundGenerator(new SecureRandom(), rules);
        ResultUtil util = new ResultUtil(rules);
        RoundCodec codec = new RoundCodec();
        Map<String, Integer> counts = new TreeMap<>();
        LoaderLimits limits = new LoaderLimits(p);
        loss = limits.lossTarget(loss);
        long[] attempts = {0, System.nanoTime()};
        int[] phaseQuota = {0, special / 4, special / 4, special / 4, special - 3 * (special / 4)};
        try (Jedis jedis = new Jedis(host, port, cfg.build())) {
            jedis.connect();
            if (!"PONG".equalsIgnoreCase(jedis.ping())) throw new IllegalStateException("redis ping failed " + host + ":" + port);
            for (int i = 0; i < loss; i++) if (!write(jedis, game, generator.ordinary(false), util, codec, cap, counts, limits, attempts)) i--;
            for (int i = 0; i < win; i++) if (!write(jedis, game, generator.ordinary(true), util, codec, cap, counts, limits, attempts)) i--;
            for (int phase = 1; phase <= 4; phase++)
                for (int i = 0; i < phaseQuota[phase]; i++)
                    if (!write(jedis, game, generator.special(phase), util, codec, cap, counts, limits, attempts)) i--;
            System.out.printf(Locale.ROOT, "Loaded complete rounds loss=%d win=%d special=%d rulesHash=%s buckets=%s%n",
                    loss, win, special, GameRuleCore.RULES_HASH, counts);
        }
    }

    private static boolean write(Jedis jedis, String game, CompleteRound round, ResultUtil util, RoundCodec codec,
                              int cap, Map<String, Integer> counts, LoaderLimits limits, long[] attempts) {
        if (++attempts[0] > 10_000 || System.nanoTime()-attempts[1] > java.util.concurrent.TimeUnit.SECONDS.toNanos(30)) throw new IllegalStateException("Configured range or weights cannot satisfy requested category within the rejection budget (10000 candidates / 30 seconds)");
        ResultUtil.Analysis a = util.analyze(round);
        boolean specialPool = RedisKeys.index(game, a.outcome()).startsWith("MaryKeyList_");
        if (!limits.accepts(specialPool, a.integerMultiplier())) return false;
        String member = codec.encode(round);
        if (!member.equals(codec.encode(codec.decode(member)))) throw new IllegalStateException("codec roundtrip");
        if (!member.chars().allMatch(c -> c >= 32 && c <= 126)) throw new IllegalStateException("member is not ASCII");
        if (member.startsWith("{") || member.startsWith("[")) throw new IllegalStateException("member looks like JSON");
        if (a.integerMultiplier() < 0) throw new IllegalStateException("negative multiplier");
        String bucket = RedisKeys.list(game, a.outcome(), a.integerMultiplier());
        String index = RedisKeys.index(game, a.outcome());
        String ratio = Integer.toString(a.integerMultiplier());
        Transaction tx = jedis.multi();
        tx.rpush(bucket, member);
        tx.ltrim(bucket, -(specialPool ? limits.specialCap : cap), -1);
        tx.zadd(index, (double) a.integerMultiplier(), ratio);
        tx.exec();
        counts.merge(bucket, 1, Integer::sum);
        attempts[0]=0; attempts[1]=System.nanoTime(); return true;
    }

    private static String req(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("missing " + key);
        return v.trim();
    }

    private static int num(Properties p, String key, int min, int max) {
        int v = Integer.parseInt(req(p, key));
        if (v < min || v > max) throw new IllegalArgumentException(key + " out of range");
        return v;
    }
}
