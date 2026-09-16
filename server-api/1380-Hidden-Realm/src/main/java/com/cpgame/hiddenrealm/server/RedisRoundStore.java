package com.cpgame.hiddenrealm.server;

import com.cpgame.demo.redis.RedisFloorLookup;

import com.cpgame.hiddenrealm.core.CompleteRound;
import com.cpgame.hiddenrealm.core.GameRuleCore;
import com.cpgame.hiddenrealm.core.RedisKeys;
import com.cpgame.hiddenrealm.core.ResultUtil;
import com.cpgame.hiddenrealm.core.RoundCodec;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.Jedis;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

final class RedisRoundStore implements AutoCloseable {
    enum Selection { LOSS, WIN, SPECIAL, ANY }
    record Claim(String member, CompleteRound round, ResultUtil.Analysis analysis) {}
    static final class CacheEmptyException extends RuntimeException {
        CacheEmptyException(String message) { super(message); }
    }

    private final Jedis jedis;
    private final String game;
    private final RoundCodec codec = new RoundCodec();
    private final GameRuleCore rules = new GameRuleCore();
    private final ResultUtil util = new ResultUtil(rules);
    private final SecureRandom random = new SecureRandom();

    RedisRoundStore(Properties p) {
        String host = p.getProperty("redis.host");
        int port = Integer.parseInt(p.getProperty("redis.port", "8021"));
        int db = Integer.parseInt(p.getProperty("redis.database", "0"));
        if (!"18.234.101.161".equals(host) || port != 8021 || db < 0)
            throw new IllegalArgumentException("authorized Redis endpoint is 18.234.101.161:8021 DB15");
        game = p.getProperty("redis.game-id", "8001380");
        RedisKeys.prefix(game);
        DefaultJedisClientConfig.Builder cfg = DefaultJedisClientConfig.builder().database(db)
                .connectionTimeoutMillis(Integer.parseInt(p.getProperty("redis.connect-timeout-ms", "5000")))
                .socketTimeoutMillis(Integer.parseInt(p.getProperty("redis.socket-timeout-ms", "10000")));
        String user = p.getProperty("redis.username", "").trim(), pass = p.getProperty("redis.password", "").trim();
        if (!user.isEmpty()) cfg.user(user);
        if (!pass.isEmpty()) cfg.password(pass);
        jedis = new Jedis(host, port, cfg.build());
        jedis.connect();
        if (!"PONG".equalsIgnoreCase(jedis.ping())) throw new IllegalStateException("redis ping failed " + host + ":" + port);
    }

    synchronized Claim claim(Selection selection) {
        boolean loss = selection == Selection.LOSS
                || (selection == Selection.ANY && random.nextBoolean());
        boolean includeNormalWin = selection == Selection.WIN || (selection == Selection.ANY && !loss);
        boolean includeSpecial = selection == Selection.SPECIAL || (selection == Selection.ANY && !loss);
        if (selection == Selection.WIN) { loss = false; includeSpecial = false; }
        List<int[]> available = new ArrayList<>();
        if (loss) collect(available, false, true);
        if (includeNormalWin) collect(available, false, false);
        if (includeSpecial) collect(available, true, false);
        if (available.isEmpty()) throw new CacheEmptyException("selected " + (loss ? "loss" : "win") + " pool has no complete member");
        int[] chosen = available.get(random.nextInt(available.size()));
        boolean special = chosen[0] == 1;
        int multiplier = chosen[1];
        String list = special ? RedisKeys.maryList(game, multiplier) : RedisKeys.normalList(game, multiplier);
        long len = jedis.llen(list);
        if (len <= 0) throw new CacheEmptyException("selected member was concurrently exhausted");
        String member = jedis.lindex(list, random.nextInt((int) Math.min(len, Integer.MAX_VALUE)));
        if (member == null) throw new CacheEmptyException("selected member was concurrently exhausted");
        CompleteRound round = codec.decode(member);
        rules.validateRound(round);
        ResultUtil.Analysis analysis = util.analyze(round);
        if (analysis.integerMultiplier() != multiplier) throw new IllegalStateException("Redis member does not match its pool");
        if (special != RedisKeys.special(analysis.outcome())) throw new IllegalStateException("Redis member does not match its pool");
        return new Claim(member, round, analysis);
    }

    private void collect(List<int[]> available, boolean special, boolean lossOnly) {
        Integer multiplier = RedisFloorLookup.choose(this::floorCommand,
                special ? RedisKeys.maryIndex(game) : RedisKeys.normalIndex(game),
                m -> special ? RedisKeys.maryList(game, m) : RedisKeys.normalList(game, m),
                random, lossOnly ? 0 : 1, lossOnly ? 0 : Integer.MAX_VALUE);
        if (multiplier != null) available.add(new int[]{special ? 1 : 0, multiplier});
    }
    private Object floorCommand(String... args) {
        return switch (args[0]) {
            case "ZREVRANGEBYSCORE" -> jedis.zrevrangeByScore(args[1], args[2], args[3], 0, 1);
            case "LLEN" -> jedis.llen(args[1]);
            default -> throw new IllegalArgumentException("unexpected lookup command");
        };
    }
    

    @Override public void close() { jedis.close(); }
}
