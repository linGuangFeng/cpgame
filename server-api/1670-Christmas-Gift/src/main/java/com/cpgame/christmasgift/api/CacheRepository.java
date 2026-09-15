package com.cpgame.christmasgift.api;

import com.cpgame.christmasgift.core.GameRuleCore;
import com.cpgame.christmasgift.core.MinimalRoundFactCodec;
import com.cpgame.christmasgift.core.RedisClient;
import com.cpgame.christmasgift.core.ResultUtil;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

final class CacheRepository {
    enum Pool { ORDINARY_LOSS, ORDINARY_WIN, FEATURE }
    private final ServerConfig config;
    private final SecureRandom random = new SecureRandom();
    private final MinimalRoundFactCodec codec = new MinimalRoundFactCodec();
    CacheRepository(ServerConfig config) { this.config = config; }

    GameRuleCore.CompleteRound take(Pool pool) throws Exception {
        String indexKey = pool == Pool.FEATURE ? String.format("MaryKeyList_%09d", config.gameId()) : String.format("PerKeyList_%09d", config.gameId());
        try (RedisClient redis = config.openRedis()) {
            List<Integer> multipliers = new ArrayList<>();
            for (String value : redis.zrange(indexKey)) {
                int multiplier = Integer.parseInt(value);
                if (pool == Pool.FEATURE || (pool == Pool.ORDINARY_LOSS ? multiplier == 0 : multiplier > 0)) multipliers.add(multiplier);
            }
            while (!multipliers.isEmpty()) {
                int chosen = multipliers.remove(random.nextInt(multipliers.size()));
                String listKey = pool == Pool.FEATURE
                    ? String.format("MaryLog:%09d:%06d", config.gameId(), chosen)
                    : String.format("BetLog:0%08d:%06d", config.gameId(), chosen);
                long len = redis.llen(listKey);
                if (len <= 0) continue;
                String member = redis.lindex(listKey, random.nextInt((int) Math.min(len, Integer.MAX_VALUE)));
                if (member == null) continue;
                GameRuleCore.CompleteRound round = codec.decode(member);
                if (ResultUtil.multiplier(round) != chosen) throw new IllegalStateException("Redis member multiplier mismatch");
                if (pool == Pool.FEATURE && round.mode() != GameRuleCore.Mode.CHRISTMAS_GIFT_FEATURE) throw new IllegalStateException("wrong special pool member");
                if (pool != Pool.FEATURE && round.mode() != GameRuleCore.Mode.ORDINARY) throw new IllegalStateException("wrong ordinary pool member");
                return round;
            }
        }
        throw new IllegalStateException("requested Redis cache pool is empty: " + pool);
    }
}
