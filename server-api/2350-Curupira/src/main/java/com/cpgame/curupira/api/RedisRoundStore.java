package com.cpgame.curupira.api;

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
        String index = keys.indexFor(kind, GAME_ID);
        List<String> buckets = strings(redis.command("ZRANGE", index, "0", "-1"));
        if (kind == Kind.LOSS) {
            CompleteRoundFact zero = scanList(keys.listFor(kind, 0, GAME_ID), kind, 0, 0);
            if (zero != null) return zero;
        }
        for (String bucket : buckets) {
            int multiplier = Integer.parseInt(bucket);
            if (multiplier < minMultiplier || multiplier > maxMultiplier) continue;
            if (kind == Kind.LOSS && multiplier != 0) continue;
            if (kind != Kind.LOSS && kind.ordinary() && multiplier == 0) continue;
            CompleteRoundFact found = scanList(keys.listFor(kind, multiplier, GAME_ID),
                    kind, minMultiplier, maxMultiplier);
            if (found != null) return found;
        }
        throw empty(kind + " pool is empty for o=" + minMultiplier + ".." + maxMultiplier);
    }

    private CompleteRoundFact scanList(String listKey, Kind kind, int minMultiplier, int maxMultiplier) throws IOException {
        for (String member : strings(redis.command("LRANGE", listKey, "0", "-1"))) {
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

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> values = new ArrayList<>(list.size());
        for (Object item : list) values.add(String.valueOf(item));
        return values;
    }

    private static IllegalStateException empty(String detail) {
        return new IllegalStateException("Redis round cache unavailable/empty at 18.234.101.161:8021 db=15 gameId=2350: " + detail);
    }

    @Override public void close() {
        try { redis.close(); } catch (IOException ignored) { }
    }
}
