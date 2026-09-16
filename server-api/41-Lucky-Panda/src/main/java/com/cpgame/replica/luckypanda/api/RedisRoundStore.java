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
import java.util.List;
import java.util.Properties;

/**
 * Demo 只从配置的 Redis 读取一条完整局 member。
 * 先随机中/不中；中奖随机目标倍数，向下找最近有数据的桶，再随机读取完整局。
 * 连消/免费不在这里抽。购买不是玩法，因此没有 BUY 桶；Scatter 免费进 Mary 索引。
 * runtimeIndependentLoss.supported=true 不是当场出牌许可：不中奖走 0 倍池。
 */
final class RedisRoundStore implements AutoCloseable {
    static final int MAX_CONSECUTIVE = 10;

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
            throw new IllegalStateException("Redis round cache is empty for gameId=" + gameId);
        }
        return claimed;
    }

    private ClaimedRound claimLoss(SecureRandom random) throws IOException {
        return readMember(false, 0, random);
    }

    private ClaimedRound claimWin(SecureRandom random) throws IOException {
        boolean firstSpecial = random.nextBoolean();
        for (boolean special : new boolean[]{firstSpecial, !firstSpecial}) {
            String index = index(special);
            List<Object> highest = asList(redis.command("ZREVRANGE", index, "0", "0"));
            if (highest.isEmpty()) continue;
            int maximum = Integer.parseInt(highest.get(0).toString());
            if (maximum <= 0) continue;
            int target = random.nextInt(maximum) + 1;
            ClaimedRound found = atOrBelow(special, target, random);
            if (found != null) return found;
        }
        return null;
    }

    ClaimedRound atOrBelow(boolean special, int target, SecureRandom random) throws IOException {
        if (target < 1) return null;
        String upper = Integer.toString(target);
        int previous = 0;
        while (true) {
            List<Object> found = asList(redis.command("ZREVRANGEBYSCORE", index(special), upper, "0", "LIMIT", "0", "1"));
            if (found.isEmpty()) return null;
            int ratio = Integer.parseInt(found.get(0).toString());
            if (ratio < 0 || ratio > target || (previous > 0 && ratio >= previous)) {
                throw new IllegalStateException("Redis multiplier index is inconsistent");
            }
            ClaimedRound round = readMember(special, ratio, random);
            if (round != null) return round;
            // Only an empty/disappeared selected bucket needs another downward lookup.
            previous = ratio;
            upper = "(" + ratio;
        }
    }

    private String index(boolean special) {
        return special ? RedisKeyContract.specialIndex(gameId) : RedisKeyContract.normalIndex(gameId);
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
        Object member = redis.command("LINDEX", list, Long.toString(random.nextLong(len)));
        if (member == null) return null;
        String payload = member.toString();
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

}
