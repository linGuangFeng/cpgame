package com.cpgame.monsterslayer.loader;

import com.cpgame.monsterslayer.core.MinimalRoundFactCodec;
import com.cpgame.monsterslayer.core.ResultUtil;
import com.cpgame.monsterslayer.generator.GeneratorConfig;
import com.cpgame.monsterslayer.redis.RedisConnection;
import com.cpgame.monsterslayer.redis.RedisKeyContract;
import java.nio.file.Path;
import java.util.List;

/** Read-only post-load verifier used by delivery checks. */
public final class RedisVerifier {
    private RedisVerifier() { }
    public static void main(String[] args) throws Exception {
        GeneratorConfig c = GeneratorConfig.load(Path.of(args.length == 0 ? "generator.properties" : args[0]).toAbsolutePath());
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec();
        try (RedisConnection r = RedisConnection.connect(c)) {
            long normalMembers = count(r, codec, c.redisGameId, RedisKeyContract.normalIndex(c.redisGameId), false, 0);
            long buy1 = count(r, codec, c.redisGameId, RedisKeyContract.buyIndex(c.redisGameId, 3), true, 3);
            long buy2 = count(r, codec, c.redisGameId, RedisKeyContract.buyIndex(c.redisGameId, 4), true, 4);
            long buy3 = count(r, codec, c.redisGameId, RedisKeyContract.buyIndex(c.redisGameId, 5), true, 5);
            for (int buyType : new int[]{4, 5}) {
                String redundant = RedisKeyContract.buyPerKeyIndex(c.redisGameId, buyType);
                if (toLong(r.command("EXISTS", redundant)) != 0)
                    throw new IllegalStateException("redundant purchase index must be removed: " + redundant);
            }
            if (c.redisGameId == RedisKeyContract.GAME_ID) {
                Object leftover0 = r.command("EXISTS", RedisKeyContract.legacyBuyIndex(3));
                Object leftover1 = r.command("EXISTS", RedisKeyContract.legacyBuyIndex(4));
                Object leftover2 = r.command("EXISTS", RedisKeyContract.legacyBuyIndex(5));
                if (!Long.valueOf(0).equals(toLong(leftover0)) || !Long.valueOf(0).equals(toLong(leftover1)) || !Long.valueOf(0).equals(toLong(leftover2)))
                    throw new IllegalStateException("legacy PerKeyListt_* indexes must be deleted");
            }
            System.out.printf("REDIS_VERIFY_OK ordinaryMembers=%d buy3=%d buy4=%d buy5=%d gameId=%d%n",
                    normalMembers, buy1, buy2, buy3, c.redisGameId);
        }
    }
    private static long count(RedisConnection r, MinimalRoundFactCodec codec, long gameId, String index, boolean buy, int buyType) throws Exception {
        Object raw = r.command("ZRANGE", index, "0", "-1");
        if (!(raw instanceof List<?> buckets) || buckets.isEmpty()) return 0;
        long members = 0;
        for (Object bucket : buckets) {
            int expected = Integer.parseInt(bucket.toString());
            String key = buy ? RedisKeyContract.buyList(gameId, buyType, expected) : RedisKeyContract.normalList(gameId, expected);
            members += ((Number) r.command("LLEN", key)).longValue();
            Object values = r.command("LRANGE", key, "0", "-1");
            if (!(values instanceof List<?> list)) continue;
            for (Object value : list) {
                int actual = ResultUtil.redisMultiplierCenti(codec.decode(value.toString()));
                if (actual != expected) throw new IllegalStateException("bucket mismatch " + key + " expected=" + expected + " actual=" + actual);
                if (value.toString().startsWith("{")) throw new IllegalStateException("JSON member forbidden");
            }
        }
        return members;
    }
    private static long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(value));
    }
}
