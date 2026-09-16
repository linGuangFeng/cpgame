package com.cpgame.sambasensation.server;

import com.cpgame.demo.redis.RedisFloorLookup;

import com.cpgame.sambasensation.core.GameRuleCore;
import com.cpgame.sambasensation.core.MinimalRoundFactCodec;
import com.cpgame.sambasensation.core.ResultUtil;
import com.cpgame.sambasensation.redis.RedisKeyContract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Redis模板入口。运行时普通页不经过本类；这里只领取购买完整局、普通正奖模板和五步Free尾部。
 * 已领取的Free后续响应只读取Session持有的事实，绝不再次抽倍率或访问fixture/History。
 */
final class RedisRoundStore implements AutoCloseable {
    private final long GAME_ID;
    private static final int COIN_REWARD_BUCKET_WEIGHT = 20;
    private final RedisCommands redis;
    private final MinimalRoundFactCodec codec = new MinimalRoundFactCodec();

    RedisRoundStore(RedisCommands redis, long gameId) {
        this.redis = redis;
        this.GAME_ID = gameId;
        if (gameId <= 0) throw new IllegalArgumentException("redis.game-id must be positive");
        SharedRuleCoreContract.verify();
    }

    static RedisRoundStore connect(Properties config) throws IOException {
        long gameId = Long.parseLong(config.getProperty("redis.game-id", "8002290").trim());
        return new RedisRoundStore(SocketRedisCommands.connect(config), gameId);
    }

    /** 原页面 Init 的静态盘面同样来自缓存；只窥视0倍局，不消费、不创建付费 Round。 */
    synchronized GameRuleCore.CompleteRoundFact peekInitialLoss() throws IOException {
        String key = RedisKeyContract.normalList(GAME_ID, 0);
        for (Object raw : asList(redis.command("LRANGE", key, "0", "-1"))) {
            if (!codec.supports(String.valueOf(raw))) continue;
            ClaimedRound checked = verify(raw.toString(), false, 0, ClaimPurpose.PAID);
            if (checked.kind() == GameRuleCore.RoundClass.ORDINARY_LOSS) return checked.fact();
        }
        throw empty("Init 需要当前SS2版本的0倍预生成局");
    }

    synchronized ClaimedRound claimPaid(SecureRandom random, int requestedBetType,
                                        GameRuleCore.CollectionState collectionState) throws IOException {
        if (random == null) throw new IllegalArgumentException("SecureRandom required");
        if (requestedBetType < 1 || requestedBetType > 3) throw new IllegalArgumentException("bet_type must be 1..3");
        boolean wantWin = random.nextBoolean();
        ClaimedRound normal = claimPool(random, false, wantWin, ClaimPurpose.PAID, requestedBetType, collectionState);
        if (!wantWin) {
            if (normal == null) throw empty("未中奖池为空");
            return normal;
        }
        ClaimedRound special = claimPool(random, true, true, ClaimPurpose.PAID, requestedBetType, collectionState);
        if (normal == null && special == null) throw empty("目标以下中奖池为空");
        if (normal == null) return special;
        if (special == null) return normal;
        int specialWeight = special.kind() == GameRuleCore.RoundClass.COIN_COLLECTION_REWARD ? COIN_REWARD_BUCKET_WEIGHT : 1;
        return random.nextInt(specialWeight + 1) == 0 ? normal : special;
    }

    /** 购买就是同一 mali/Free 状态；只从 Mary 池领取带购买触发页的完整六步 member。 */
    synchronized ClaimedRound claimFeatureBuy(SecureRandom random, GameRuleCore.CollectionState collectionState) throws IOException {
        ClaimedRound claimed = claimPool(random, true, true, ClaimPurpose.FEATURE_BUY, 1, collectionState);
        if (claimed == null) throw empty("目标以下无购买所需的 mali member");
        return claimed;
    }

    /** 为运行时金币满盘局领取无Scatter、无金币副作用且不超过剩余预算的普通中奖牌面。 */
    synchronized ClaimedRound claimPurePaidTemplateAtMost(SecureRandom random, int betType,
                                                           int maxMultiplier) throws IOException {
        if (maxMultiplier <= 0) return null;
        var buckets = RedisFloorLookup.open(redis::command, RedisKeyContract.normalIndex(GAME_ID, betType),
                m -> RedisKeyContract.normalList(GAME_ID, m, betType), random, 1, maxMultiplier);
        Integer multiplier;
        while ((multiplier = buckets.next()) != null) {
            ClaimedRound claimed = readEligible(random, RedisKeyContract.normalList(GAME_ID, multiplier, betType),
                    false, multiplier, ClaimPurpose.PAID,
                    checked -> checked.fact().betType() == betType && checked.fact().scatterDelta() == 0
                            && checked.kind() == GameRuleCore.RoundClass.ORDINARY_WIN);
            if (claimed != null) return claimed;
        }
        return null;
    }

    /** 只领取缓存六步局的五个Free尾页；触发页由运行时生成器负责。 */
    synchronized TailTemplate claimFreeTailAtMost(SecureRandom random, int maxMultiplier) throws IOException {
        if (maxMultiplier < 0) return null;
        // The index is the complete round's payout, not the five-page tail's payout.
        var buckets = RedisFloorLookup.open(redis::command, RedisKeyContract.specialIndex(GAME_ID),
                m -> RedisKeyContract.specialList(GAME_ID, m), random, 0, Integer.MAX_VALUE);
        Integer multiplier;
        while ((multiplier = buckets.next()) != null) {
            ClaimedRound selected = readEligible(random, RedisKeyContract.specialList(GAME_ID, multiplier),
                    true, multiplier, ClaimPurpose.PAID,
                    checked -> checked.kind() == GameRuleCore.RoundClass.FREE_SPINS_SPECIAL
                            && checked.fact().steps().size() == 6 && tailMultiplier(checked.fact()) <= maxMultiplier);
            if (selected != null) return new TailTemplate(selected.fact().steps().subList(1, 6), tailMultiplier(selected.fact()));
        }
        return null;
    }

    private static int tailMultiplier(GameRuleCore.CompleteRoundFact fact) {
        int total = 0;
        for (int step = 1; step < 6; step++) total += ResultUtil.evaluateDelivery(fact, step).multiplier();
        return total;
    }

    

    

    

    

    

    

    private ClaimedRound claimPool(SecureRandom random, boolean special, boolean positive,
            ClaimPurpose purpose, int betType, GameRuleCore.CollectionState collectionState) throws IOException {
        var buckets = RedisFloorLookup.open(redis::command,
                special ? RedisKeyContract.specialIndex(GAME_ID) : RedisKeyContract.normalIndex(GAME_ID),
                m -> special ? RedisKeyContract.specialList(GAME_ID, m) : RedisKeyContract.normalList(GAME_ID, m),
                random, positive ? 1 : 0, positive ? Integer.MAX_VALUE : 0);
        Integer multiplier;
        while ((multiplier = buckets.next()) != null) {
            String key = special ? RedisKeyContract.specialList(GAME_ID, multiplier) : RedisKeyContract.normalList(GAME_ID, multiplier);
            ClaimedRound claimed = readEligible(random, key, special, multiplier, purpose, checked ->
                    GameRuleCore.canApplyCollectionTransition(collectionState, checked.fact())
                    && (purpose == ClaimPurpose.FEATURE_BUY
                        ? checked.fact().entryKind() == GameRuleCore.EntryKind.FEATURE_BUY_INITIAL
                        : checked.fact().entryKind() == GameRuleCore.EntryKind.PAID_INITIAL && checked.fact().betType() == betType));
            if (claimed != null) return claimed;
        }
        return null;
    }

    private ClaimedRound readEligible(SecureRandom random, String key, boolean special, int multiplier,
            ClaimPurpose purpose, java.util.function.Predicate<ClaimedRound> accepts) throws IOException {
        long length = number(redis.command("LLEN", key));
        if (length <= 0) return null;
        long start = random.nextLong(length);
        for (long visited = 0; visited < length; visited++) {
            Object raw = redis.command("LINDEX", key, Long.toString((start + visited) % length));
            if (raw == null) continue;
            try {
                ClaimedRound checked = verify(raw.toString(), special, multiplier, purpose);
                if (accepts.test(checked)) return checked;
            } catch (IllegalArgumentException | IllegalStateException ignored) { }
        }
        return null;
    }

    private ClaimedRound verify(String payload, boolean special, int multiplier, ClaimPurpose purpose) {
        if (payload.isEmpty() || payload.charAt(0) == '{' || payload.charAt(0) == '[') {
            throw new IllegalStateException("Redis member 必须是极简 ASCII，禁止整份 JSON");
        }
        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(payload)) throw new IllegalStateException("Redis member 非 ASCII");
        ResultUtil.Evaluation evaluation = codec.verify(payload);
        GameRuleCore.CompleteRoundFact fact = codec.decode(payload);
        if (evaluation.multiplier() != multiplier) throw new IllegalStateException("Redis 倍率索引与共享 ResultUtil 不一致");
        if (!special && multiplier == 0 && evaluation.roundClass() != GameRuleCore.RoundClass.ORDINARY_LOSS) {
            throw new IllegalStateException("0倍普通池不是 ORDINARY_LOSS");
        }
        if (!special && multiplier > 0 && evaluation.roundClass() != GameRuleCore.RoundClass.ORDINARY_WIN) {
            throw new IllegalStateException("正倍普通池不是 ORDINARY_WIN");
        }
        if (special && evaluation.roundClass() != GameRuleCore.RoundClass.FREE_SPINS_SPECIAL
                && evaluation.roundClass() != GameRuleCore.RoundClass.COIN_COLLECTION_REWARD) {
            throw new IllegalStateException("Mary 池 member 不是已确认特殊结果");
        }
        if (purpose == ClaimPurpose.FEATURE_BUY
                && (fact.entryKind() != GameRuleCore.EntryKind.FEATURE_BUY_INITIAL
                || evaluation.roundClass() != GameRuleCore.RoundClass.FREE_SPINS_SPECIAL)) {
            throw new IllegalStateException("购买只能领取购买入口 Free Spins member");
        }
        if (purpose == ClaimPurpose.PAID && fact.entryKind() != GameRuleCore.EntryKind.PAID_INITIAL) {
            throw new IllegalStateException("自然付费入口不能领取购买触发页");
        }
        return new ClaimedRound(fact, evaluation, evaluation.roundClass(), special, multiplier, payload);
    }

    private long length(boolean special, int multiplier) throws IOException {
        String key = special ? RedisKeyContract.specialList(GAME_ID, multiplier) : RedisKeyContract.normalList(GAME_ID, multiplier);
        return number(redis.command("LLEN", key));
    }

    private static long number(Object value) { return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value)); }
    @SuppressWarnings("unchecked") private static List<Object> asList(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }
    private static IllegalStateException empty(String detail) {
        return new IllegalStateException("Redis round cache unavailable/empty at 18.234.101.161:8021 db=15 gameId=2290: " + detail);
    }

    @Override public void close() throws IOException { redis.close(); }

    enum ClaimPurpose { PAID, FEATURE_BUY }
    record ClaimedRound(GameRuleCore.CompleteRoundFact fact, ResultUtil.Evaluation evaluation,
                        GameRuleCore.RoundClass kind, boolean special, int multiplier, String member) { }
    record TailTemplate(List<GameRuleCore.Step> steps, int multiplier) { }
}
