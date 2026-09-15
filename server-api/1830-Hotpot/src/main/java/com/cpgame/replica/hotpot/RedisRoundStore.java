package com.cpgame.replica.hotpot;

import com.hd.pg.appapi.business.model.cpgame.hotpot.GameRuleCore;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotGameRuleCore;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotRoundKind;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Demo 只从 Redis db=15 领取一条完整局 member。
 * 先随机中/不中，再在对应奖池已有倍率中随机一个。连消/免费不在这里抽。
 * 本游戏无购买入口，因此没有 BUY 桶；Scatter 免费进 Mary 索引（平台特殊池名，不是 1809 Mary 玩法）。
 * runtimeIndependentLoss.supported=true 不是当场出牌许可：不中奖走 0 倍池。
 */
public final class RedisRoundStore implements AutoCloseable {
    public static final String PROTOCOL_HASH = HotpotRulesMetadata.PROTOCOL_HASH;
    private static final int MAX_CONSECUTIVE = 10;
    private static final int MAX_FREE = 30;

    private final RedisCommands redis;
    private final long gameId;
    private final GameRuleCore core;
    private final CompleteRoundCodec codec;

    public RedisRoundStore(RedisCommands redis, long gameId) {
        this.redis = redis;
        this.gameId = gameId;
        this.core = new HotpotGameRuleCore();
        this.codec = new CompleteRoundCodec();
        if (core.rawGameId() != 1830) throw new IllegalStateException("GameRuleCore gid must be 1830");
        if (!PROTOCOL_HASH.equals("01123dc898992e5e38efb1f58b816c844093dfcf07939e2cefa3153022b61c01")) {
            throw new IllegalStateException("rulesHash mismatch with protocol-handoff.json");
        }
    }

    public static RedisRoundStore connect(Properties config) throws IOException {
        long gameId = Long.parseLong(config.getProperty("redis.game-id", "8001830").trim());
        if (gameId <= 0) throw new IllegalArgumentException("redis.game-id must be positive");
        return new RedisRoundStore(SocketRedisCommands.connect(config), gameId);
    }

    /**
     * 一次付费起点只调用一次。返回的 member 已含全部连消页和免费 spin。
     * 无 Wild：符号 1-10 赔付、11 Scatter、12-23 倍率；GamePropID 只有 Scatter=11。
     */
    public ClaimedRound claim(SecureRandom random) throws IOException {
        if (random == null) throw new IllegalArgumentException("SecureRandom is required");
        boolean wantWin = random.nextBoolean();
        ClaimedRound claimed = wantWin ? claimWin(random) : claimLoss(random);
        if (claimed == null) {
            throw new IllegalStateException("Redis round cache is empty host=18.234.101.161 db=15 gameId=1830");
        }
        return claimed;
    }

    public ClaimedRound claimKind(HotpotRoundKind kind, SecureRandom random) throws IOException {
        if (random == null) throw new IllegalArgumentException("SecureRandom is required");
        if (kind == null) throw new IllegalArgumentException("kind is required");
        ClaimedRound claimed = switch (kind) {
            case ORDINARY_LOSS -> claimLoss(random);
            case ORDINARY_WIN -> claimOrdinaryWin(random);
            case SCATTER_FREE_SPINS -> claimSpecial(random);
        };
        if (claimed == null) {
            throw new IllegalStateException("Redis round cache has no " + kind
                    + " member host=18.234.101.161 db=15 gameId=1830");
        }
        if (claimed.kind() != kind) {
            throw new IllegalStateException("claimed member is " + claimed.kind() + " not " + kind);
        }
        return claimed;
    }

    private ClaimedRound claimLoss(SecureRandom random) throws IOException {
        // 未中奖完整局预写在 BetLog:...:000000，禁止 IndependentLossGenerator 顶上。
        return readMember(false, 0, random);
    }

    private ClaimedRound claimWin(SecureRandom random) throws IOException {
        List<Integer> ordinary = positiveRatios(false);
        List<Integer> special = positiveRatios(true);
        if (ordinary.isEmpty() && special.isEmpty()) return null;
        boolean useSpecial;
        if (ordinary.isEmpty()) useSpecial = true;
        else if (special.isEmpty()) useSpecial = false;
        else useSpecial = random.nextBoolean();
        return useSpecial ? claimSpecial(random) : claimOrdinaryWin(random);
    }

    private ClaimedRound claimOrdinaryWin(SecureRandom random) throws IOException {
        List<Integer> ratios = positiveRatios(false);
        if (ratios.isEmpty()) return null;
        return readMember(false, ratios.get(random.nextInt(ratios.size())), random);
    }

    private ClaimedRound claimSpecial(SecureRandom random) throws IOException {
        List<Integer> ratios = positiveRatios(true);
        if (ratios.isEmpty()) return null;
        return readMember(true, ratios.get(random.nextInt(ratios.size())), random);
    }

    private List<Integer> positiveRatios(boolean special) throws IOException {
        String index = special ? RedisKeys.maryIndex(gameId) : RedisKeys.normalIndex(gameId);
        List<Object> raw = asList(redis.command("ZRANGE", index, "0", "-1"));
        List<Integer> ratios = new ArrayList<>();
        for (Object item : raw) {
            int ratio = Integer.parseInt(item.toString());
            if (ratio <= 0) continue;
            if (llen(special, ratio) > 0) ratios.add(ratio);
        }
        return ratios;
    }

    private long llen(boolean special, int ratio) throws IOException {
        String list = special ? RedisKeys.maryList(gameId, ratio) : RedisKeys.normalList(gameId, ratio);
        Object len = redis.command("LLEN", list);
        return len instanceof Long value ? value : Long.parseLong(String.valueOf(len));
    }

    private ClaimedRound readMember(boolean special, int ratio, SecureRandom random) throws IOException {
        long len = llen(special, ratio);
        if (len <= 0) return null;
        int offset = random.nextInt((int) Math.min(len, Integer.MAX_VALUE));
        String list = special ? RedisKeys.maryList(gameId, ratio) : RedisKeys.normalList(gameId, ratio);
        Object member = redis.command("LINDEX", list, Integer.toString(offset));
        if (member == null) return null;
        String payload = member.toString();
        if (payload.isEmpty() || payload.charAt(0) == '{' || payload.charAt(0) == '[') {
            throw new IllegalStateException("cached member must be compact ASCII, not JSON");
        }
        CompleteRoundFact fact = codec.decode(payload);
        RoundVerification verification = codec.verify(fact, MAX_CONSECUTIVE, MAX_FREE);
        HotpotRoundKind kind = core.classifyRound(verification.scatterFreeSpins(), verification.multiplier());
        if (special && kind != HotpotRoundKind.SCATTER_FREE_SPINS) {
            throw new IllegalStateException("Mary pool member is not SCATTER_FREE_SPINS");
        }
        if (!special && ratio == 0 && kind != HotpotRoundKind.ORDINARY_LOSS) {
            throw new IllegalStateException("0-ratio member is not ORDINARY_LOSS");
        }
        if (!special && ratio > 0 && kind != HotpotRoundKind.ORDINARY_WIN) {
            throw new IllegalStateException("positive BetLog member is not ORDINARY_WIN");
        }
        if (verification.multiplier() != ratio) {
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

    public record ClaimedRound(CompleteRoundFact fact, RoundVerification verification, HotpotRoundKind kind,
                               boolean special, int ratio, String member) { }
}
