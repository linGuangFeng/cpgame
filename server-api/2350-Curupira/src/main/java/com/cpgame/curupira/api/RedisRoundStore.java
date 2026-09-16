package com.cpgame.curupira.api;

import com.cpgame.demo.redis.RedisFloorLookup;

import com.cpgame.curupira.codec.MinimalFactCodec;
import com.cpgame.curupira.core.ResultUtil;
import com.cpgame.curupira.model.CompleteRoundFact;
import com.cpgame.curupira.model.CompleteRoundFact.Kind;
import com.cpgame.curupira.redis.RedisContractGate;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class RedisRoundStore implements RoundSource {
    private final int GAME_ID;
    private final java.security.SecureRandom random = new java.security.SecureRandom();
    private final RedisCommands redis;
    private final MinimalFactCodec codec = new MinimalFactCodec();
    private final ResultUtil resultUtil = new ResultUtil();
    private final RedisContractGate keys = new RedisContractGate();

    RedisRoundStore(RedisCommands redis) { this(redis, 2350); }
    RedisRoundStore(RedisCommands redis, int gameId) { this.redis = redis; this.GAME_ID = gameId; }

    public static RedisRoundStore connect(Properties config) throws IOException {
        if (Integer.parseInt(config.getProperty("redis.game-id", "8002350")) <= 0) {
            throw new IllegalArgumentException("redis.game-id must be positive");
        }
        return new RedisRoundStore(SocketRedisCommands.connect(config), Integer.parseInt(config.getProperty("redis.game-id", "8002350")));
    }

    @Override public synchronized CompleteRoundFact peekLoss() {
        try {
            return find(Kind.LOSS, 0, 0);
        } catch (IOException e) {
            throw empty(e.getMessage());
        }
    }

    @Override public synchronized CompleteRoundFact claim(Kind kind) {
        return claim(kind, 0, Integer.MAX_VALUE);
    }

    @Override public synchronized CompleteRoundFact claim(Kind kind, int minMultiplier, int maxMultiplier) {
        if (kind == Kind.TRIGGER) {
            throw new IllegalStateException("trigger boards are generated live and are not stored in Redis");
        }
        try {
            return find(kind == Kind.BUY_FE ? Kind.FREE_EW : kind == Kind.BUY_HS ? Kind.HOLD : kind,
                    minMultiplier, maxMultiplier);
        } catch (IOException e) {
            throw empty(e.getMessage());
        }
    }

    @Override public synchronized CompleteRoundFact claimBuy(int gameType) {
        if (gameType == 2) return claim(Kind.FREE_EW);
        if (gameType == 3) return claim(Kind.HOLD);
        throw new IllegalArgumentException("buy game_type must be 2 or 3");
    }

    private CompleteRoundFact find(Kind kind, int minMultiplier, int maxMultiplier) throws IOException {
        int minimum = kind == Kind.LOSS ? 0 : Math.max(kind.ordinary() ? 1 : 0, minMultiplier);
        int maximum = kind == Kind.LOSS ? 0 : maxMultiplier;
        if (maximum >= minimum) {
            var buckets = RedisFloorLookup.open(redis::command, keys.indexFor(kind, GAME_ID),
                    m -> keys.listFor(kind, m, GAME_ID), random, minimum, maximum);
            Integer multiplier;
            while ((multiplier = buckets.next()) != null) {
                CompleteRoundFact found = scanList(keys.listFor(kind, multiplier, GAME_ID), kind, multiplier, multiplier);
                if (found != null) return found;
            }
        }
        throw empty(kind + " pool is empty for o=" + minMultiplier + ".." + maxMultiplier);
    }

    private CompleteRoundFact scanList(String listKey, Kind kind, int minMultiplier, int maxMultiplier) throws IOException {
        long length = Long.parseLong(redis.command("LLEN", listKey).toString());
        if (length <= 0) return null;
        long start = random.nextLong(length);
        for (long visited = 0; visited < length; visited++) {
            Object raw = redis.command("LINDEX", listKey, Long.toString((start + visited) % length));
            if (raw == null) continue;
            String member = raw.toString();
            if (member.startsWith("{") || member.startsWith("[")) {
                throw new IllegalStateException("Redis member 必须是极简 ASCII，禁止整份 JSON");
            }
            CompleteRoundFact fact = codec.decode(member);
            if (fact.kind() != kind) continue;
            int o = resultUtil.redisMultiplier(fact);
            if (o != fact.redisMultiplier()) throw new IllegalStateException("Redis multiplier mismatch");
            if (o < minMultiplier || o > maxMultiplier) continue;
            return fact;
        }
        return null;
    }



    private static IllegalStateException empty(String detail) {
        return new IllegalStateException("Redis round cache unavailable/empty at 18.234.101.161:8021 db=15 gameId=2350: " + detail);
    }

    @Override public void close() {
        try { redis.close(); } catch (IOException ignored) { }
    }
}
