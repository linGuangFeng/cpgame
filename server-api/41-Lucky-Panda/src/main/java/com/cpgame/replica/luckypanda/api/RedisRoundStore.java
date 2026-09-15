package com.cpgame.replica.luckypanda.api;

import com.cpgame.replica.luckypanda.CompleteRoundCodec;
import com.cpgame.replica.luckypanda.CompleteRoundFact;
import com.cpgame.replica.luckypanda.LuckyPandaRulesMetadata;
import com.cpgame.replica.luckypanda.RedisKeyContract;
import com.cpgame.replica.luckypanda.RoundVerification;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.GameRuleCore;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.RoundClass;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Demo 只从 Redis db=15 领取一条完整局 member。
 * 先随机中/不中，再在对应奖池已有倍率中随机一个，一次 LPOP 整局。
 * 连消/免费不在这里抽。购买不是玩法，因此没有 BUY 桶；Scatter 免费进 Mary 索引。
 * runtimeIndependentLoss.supported=true 不是当场出牌许可：不中奖走 0 倍池。
 */
final class RedisRoundStore implements AutoCloseable {
    static final int MAX_CONSECUTIVE = 10;
    private static final int GID = GameRuleCore.GAME_ID;

    private final RedisCommands redis;
    private final long gameId;
    private final CompleteRoundCodec codec = new CompleteRoundCodec();

    RedisRoundStore(RedisCommands redis, long gameId) {
        this.redis = redis;
        this.gameId = gameId;
        if (gameId <= 0) throw new IllegalStateException("redis.game-id must be positive");
        if (!GameRuleCore.RULES_HASH.equals(LuckyPandaRulesMetadata.HASH)) {
            throw new IllegalStateException("rulesHash mismatch with protocol-handoff.json");
        }
    }

    static RedisRoundStore connect(Properties config) throws IOException {
        long gameId = Long.parseLong(config.getProperty("redis.game-id", "8000041").trim());
        if (gameId <= 0) throw new IllegalArgumentException("redis.game-id must be positive");
        return new RedisRoundStore(SocketRedisCommands.connect(config), gameId);
    }

    /**
     * 一次付费起点只调用一次。返回的 member 已含全部连消页和免费 spin。
     * Wild 不是触发符：抓包页最多 3 个 RLE block / 6 格，列最多 2 block / 5 格。
     */
    synchronized ClaimedRound claim(SecureRandom random) throws IOException {
        if (random == null) throw new IllegalArgumentException("SecureRandom is required");
        boolean wantWin = random.nextBoolean();
        ClaimedRound claimed = wantWin ? claimWin(random) : claimLoss(random);
        if (claimed == null) claimed = wantWin ? claimLoss(random) : claimWin(random);
        if (claimed == null) {
            throw new IllegalStateException("Redis round cache is empty host=18.234.101.161 db=15 gameId=41");
        }
        return claimed;
    }

    private ClaimedRound claimLoss(SecureRandom random) throws IOException {
        return readMember(false, 0, random);
    }

    private ClaimedRound claimWin(SecureRandom random) throws IOException {
        List<Bucket> buckets = new ArrayList<>();
        collectPositive(false, buckets);
        collectPositive(true, buckets);
        if (buckets.isEmpty()) return null;
        Bucket bucket = buckets.get(random.nextInt(buckets.size()));
        return readMember(bucket.special, bucket.multiplier, random);
    }

    private void collectPositive(boolean special, List<Bucket> target) throws IOException {
        String index = special ? RedisKeyContract.specialIndex(gameId) : RedisKeyContract.normalIndex(gameId);
        List<Object> raw = asList(redis.command("ZRANGE", index, "0", "-1"));
        for (Object item : raw) {
            int ratio = Integer.parseInt(item.toString());
            if (ratio <= 0) continue;
            if (llen(special, ratio) > 0) target.add(new Bucket(special, ratio));
        }
    }

    private long llen(boolean special, int ratio) throws IOException {
        String list = special ? RedisKeyContract.specialList(gameId, ratio) : RedisKeyContract.normalList(gameId, ratio);
        Object len = redis.command("LLEN", list);
        return len instanceof Long value ? value : Long.parseLong(String.valueOf(len));
    }

    private ClaimedRound readMember(boolean special, int ratio, SecureRandom random) throws IOException {
        if (random == null) throw new IllegalArgumentException("SecureRandom is required");
        long len = llen(special, ratio);
        if (len <= 0) return null;
        String list = special ? RedisKeyContract.specialList(gameId, ratio) : RedisKeyContract.normalList(gameId, ratio);
        Object popped = redis.command("LPOP", list);
        if (popped == null) return null;
        String payload = popped.toString();
        if (payload.isEmpty() || payload.charAt(0) == '{' || payload.charAt(0) == '[') {
            throw new IllegalStateException("cached member must be compact ASCII, not JSON");
        }
        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(payload)) {
            throw new IllegalStateException("cached member is not US-ASCII");
        }
        CompleteRoundFact fact = codec.decode(payload);
        RoundVerification verification = codec.verify(fact, MAX_CONSECUTIVE);
        RoundClass kind = verification.roundClass();
        if (special && kind != RoundClass.SCATTER_FREE) {
            throw new IllegalStateException("Mary pool member is not SCATTER_FREE");
        }
        if (!special && ratio == 0 && kind != RoundClass.ORDINARY_LOSS) {
            throw new IllegalStateException("0-ratio member is not ORDINARY_LOSS");
        }
        if (!special && ratio > 0 && kind != RoundClass.ORDINARY_WIN) {
            throw new IllegalStateException("positive BetLog member is not ORDINARY_WIN");
        }
        if (verification.actualMultiplier() != ratio) {
            throw new IllegalStateException("cached member failed ResultUtil multiplier check");
        }
        return new ClaimedRound(fact, verification, kind, special, ratio, payload);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> list) return (List<Object>) list;
        return List.of();
    }

    @Override
    public void close() throws IOException {
        redis.close();
    }

    record ClaimedRound(CompleteRoundFact fact, RoundVerification verification, RoundClass kind,
                        boolean special, int ratio, String member) { }

    private record Bucket(boolean special, int multiplier) { }
}
