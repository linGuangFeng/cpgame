package com.cpgame.monsterslayer.server;

import com.cpgame.monsterslayer.core.GameRuleCore;
import com.cpgame.monsterslayer.core.MinimalRoundFactCodec;
import com.cpgame.monsterslayer.core.ResultUtil;
import com.cpgame.monsterslayer.redis.RedisKeyContract;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.random.RandomGenerator;

final class RedisRoundStore implements AutoCloseable {
    enum Pool { LOSS, WIN, BUY3, BUY4, BUY5 }
    private final RedisCommands redis;
    private final long gameId;
    private final MinimalRoundFactCodec codec = new MinimalRoundFactCodec();
    private final RandomGenerator random;
    RedisRoundStore(RedisCommands redis) { this(redis, new SecureRandom()); }
    RedisRoundStore(RedisCommands redis, RandomGenerator random) { this(redis, random, 2300); }
    RedisRoundStore(RedisCommands redis, RandomGenerator random, long gameId) {
        this.gameId = gameId;
        this.redis = redis;
        this.random = java.util.Objects.requireNonNull(random);
    }
    static RedisRoundStore connect(Properties c) throws IOException {
        if (Integer.parseInt(c.getProperty("redis.game-id", "8002300")) <= 0)
            throw new IllegalArgumentException("redis.game-id must be positive");
        return new RedisRoundStore(SocketRedisCommands.connect(c), new SecureRandom(), Long.parseLong(c.getProperty("redis.game-id", "8002300")));
    }
    GameRuleCore.CompleteRound peekLoss() throws IOException {
        Object v = redis.command("LINDEX", RedisKeyContract.normalList(gameId, 0), "0");
        if (v != null) return verify(v.toString(), Pool.LOSS, 0);
        throw empty("ordinary loss pool is empty");
    }
    synchronized Claimed claimPaidRound() throws IOException {
        return claimOutcome(random.nextBoolean());
    }
    synchronized Claimed claimOutcome(boolean winning) throws IOException {
        Map<Integer, List<Pool>> available = new TreeMap<>();
        for (String value : buckets(RedisKeyContract.normalIndex(gameId))) {
            int m = multiplier(value);
            if ((m > 0) != winning) continue;
            available.computeIfAbsent(m, ignored -> new ArrayList<>())
                    .add(winning ? Pool.WIN : Pool.LOSS);
        }
        return takeRandom(available, winning ? "WIN" : "LOSS", 0);
    }
    synchronized Claimed claim(Pool pool) throws IOException {
        if (pool == Pool.BUY3) return claimBuy(3);
        if (pool == Pool.BUY4) return claimBuy(4);
        if (pool == Pool.BUY5) return claimBuy(5);
        Map<Integer, List<Pool>> available = new TreeMap<>();
        for (String value : buckets(RedisKeyContract.normalIndex(gameId))) {
            int m = multiplier(value);
            if (pool == Pool.LOSS && m != 0 || pool == Pool.WIN && m == 0) continue;
            available.computeIfAbsent(m, ignored -> new ArrayList<>()).add(pool);
        }
        return takeRandom(available, pool.name(), 0);
    }
    synchronized Claimed claimBuy(int buyType) throws IOException {
        if (buyType != 3 && buyType != 4 && buyType != 5) throw new IllegalArgumentException("buy type must be 3/4/5");
        Pool pool = buyType == 3 ? Pool.BUY3 : buyType == 4 ? Pool.BUY4 : Pool.BUY5;
        Map<Integer, List<Pool>> available = new TreeMap<>();
        for (String value : buckets(RedisKeyContract.buyIndex(gameId, buyType))) {
            int m = multiplier(value);
            available.computeIfAbsent(m, ignored -> new ArrayList<>()).add(pool);
        }
        return takeRandom(available, "BUY" + buyType, buyType);
    }
    private Claimed takeRandom(Map<Integer, List<Pool>> available, String outcome, int buyType) throws IOException {
        while (!available.isEmpty()) {
            List<Integer> multipliers = new ArrayList<>(available.keySet());
            int m = multipliers.get(random.nextInt(multipliers.size()));
            List<Pool> pools = available.get(m);
            int poolIndex = pools.size() == 1 ? 0 : random.nextInt(pools.size());
            Pool pool = pools.get(poolIndex);
            String key = listKey(pool, m, buyType);
            Object length = redis.command("LLEN", key);
            long len = length instanceof Long n ? n : Long.parseLong(String.valueOf(length));
            if (len <= 0) {
                pools.remove(poolIndex);
                if (pools.isEmpty()) available.remove(m);
                continue;
            }
            int offset = random.nextInt((int) Math.min(len, Integer.MAX_VALUE));
            Object raw = redis.command("LINDEX", key, Integer.toString(offset));
            if (raw != null) return new Claimed(verify(raw.toString(), pool, m), m, raw.toString());
            pools.remove(poolIndex);
            if (pools.isEmpty()) available.remove(m);
        }
        throw empty(outcome + " multiplier lists are empty");
    }
    private String listKey(Pool pool, int m, int buyType) {
        return switch (pool) {
            case BUY3 -> RedisKeyContract.buyList(gameId, 3, m);
            case BUY4 -> RedisKeyContract.buyList(gameId, 4, m);
            case BUY5 -> RedisKeyContract.buyList(gameId, 5, m);
            default -> RedisKeyContract.normalList(gameId, m);
        };
    }
    private static int multiplier(String value) {
        int result = Integer.parseInt(value);
        if (result < 0) throw new IllegalStateException("negative multiplier bucket");
        return result;
    }
    private GameRuleCore.CompleteRound verify(String member, Pool pool, int mult) {
        if (member.startsWith("{") || member.startsWith("[")
                || !member.chars().allMatch(c -> c >= 32 && c < 127))
            throw new IllegalStateException("Redis member must be minimal printable ASCII");
        GameRuleCore.CompleteRound round = codec.decode(member);
        ResultUtil.RoundResult r = ResultUtil.evaluate(round);
        int redisMult = ResultUtil.redisMultiplierCenti(round);
        if (redisMult != mult) throw new IllegalStateException("integer multiplier bucket mismatch");
        if (pool == Pool.LOSS && r.roundClass() != GameRuleCore.RoundClass.ORDINARY_LOSS)
            throw new IllegalStateException("loss pool class mismatch");
        if (pool == Pool.WIN && r.roundClass() != GameRuleCore.RoundClass.ORDINARY_WIN)
            throw new IllegalStateException("win pool class mismatch");
        if ((pool == Pool.BUY3 || pool == Pool.BUY4 || pool == Pool.BUY5)
                && r.roundClass() != GameRuleCore.RoundClass.BUY_FEATURE)
            throw new IllegalStateException("buy pool class mismatch");
        return round;
    }
    private List<String> buckets(String indexKey) throws IOException {
        Object raw = redis.command("ZRANGE", indexKey, "0", "-1");
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).distinct().toList();
    }
    private IllegalStateException empty(String detail) {
        return new IllegalStateException("Redis round cache unavailable/empty for gameId=" + gameId + ": " + detail);
    }
    public void close() throws IOException { redis.close(); }
    record Claimed(GameRuleCore.CompleteRound round, int multiplierCenti, String member) {}
}
