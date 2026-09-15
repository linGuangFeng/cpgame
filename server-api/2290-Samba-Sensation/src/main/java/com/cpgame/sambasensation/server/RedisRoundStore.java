package com.cpgame.sambasensation.server;

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
        List<Bucket> buckets = wantWin ? paidWinningBuckets(requestedBetType, collectionState)
                : lossBuckets(requestedBetType, collectionState);
        if (buckets.isEmpty()) throw empty(wantWin ? "中奖池为空" : "未中奖池为空");
        return claimFromBuckets(random, buckets, ClaimPurpose.PAID);
    }

    /** 购买就是同一 mali/Free 状态；只从 Mary 池领取带购买触发页的完整六步 member。 */
    synchronized ClaimedRound claimFeatureBuy(SecureRandom random, GameRuleCore.CollectionState collectionState) throws IOException {
        List<Bucket> buckets = buckets(true, true, ClaimPurpose.FEATURE_BUY, 1, collectionState);
        if (buckets.isEmpty()) throw empty("购买所需的 mali member 已耗尽");
        return claimFromBuckets(random, buckets, ClaimPurpose.FEATURE_BUY);
    }

    /** 为运行时金币满盘局领取无Scatter、无金币副作用且不超过剩余预算的普通中奖牌面。 */
    synchronized ClaimedRound claimPurePaidTemplateAtMost(SecureRandom random, int betType,
                                                           int maxMultiplier) throws IOException {
        if (maxMultiplier <= 0) return null;
        List<Integer> eligible = new ArrayList<>();
        for (Object item : asList(redis.command("ZRANGE", RedisKeyContract.normalIndex(GAME_ID, betType), "0", "-1"))) {
            int multiplier = Integer.parseInt(String.valueOf(item));
            if (multiplier <= 0 || multiplier > maxMultiplier) continue;
            eligible.add(multiplier);
        }
        while (!eligible.isEmpty()) {
            int multiplier = eligible.remove(random.nextInt(eligible.size()));
            List<ClaimedRound> members = new ArrayList<>();
            String key = RedisKeyContract.normalList(GAME_ID, multiplier, betType);
            for (Object raw : asList(redis.command("LRANGE", key, "0", "-1"))) {
                try {
                    ClaimedRound checked = verify(String.valueOf(raw), false, multiplier, ClaimPurpose.PAID);
                    GameRuleCore.CompleteRoundFact fact = checked.fact();
                    if (fact.betType() == betType && fact.scatterDelta() == 0
                            && checked.kind() == GameRuleCore.RoundClass.ORDINARY_WIN) members.add(checked);
                } catch (IllegalArgumentException | IllegalStateException ignored) { }
            }
            while (!members.isEmpty()) {
                ClaimedRound selected = members.remove(random.nextInt(members.size()));
                return selected;
            }
        }
        return null;
    }

    /** 只领取缓存六步局的五个Free尾页；触发页由运行时生成器负责。 */
    synchronized TailTemplate claimFreeTailAtMost(SecureRandom random, int maxMultiplier) throws IOException {
        List<TailCandidate> candidates = new ArrayList<>();
        for (Object item : asList(redis.command("ZRANGE", RedisKeyContract.specialIndex(GAME_ID), "0", "-1"))) {
            int indexedMultiplier = Integer.parseInt(String.valueOf(item));
            String key = RedisKeyContract.specialList(GAME_ID, indexedMultiplier);
            for (Object raw : asList(redis.command("LRANGE", key, "0", "-1"))) {
                String member = String.valueOf(raw);
                try {
                    ClaimedRound checked = verify(member, true, indexedMultiplier, ClaimPurpose.PAID);
                    GameRuleCore.CompleteRoundFact fact = checked.fact();
                    if (checked.kind() != GameRuleCore.RoundClass.FREE_SPINS_SPECIAL || fact.steps().size() != 6) continue;
                    int tailMultiplier = 0;
                    for (int step = 1; step < 6; step++) tailMultiplier += ResultUtil.evaluateDelivery(fact, step).multiplier();
                    if (tailMultiplier <= maxMultiplier) candidates.add(new TailCandidate(key, member, fact.steps().subList(1, 6), tailMultiplier));
                } catch (IllegalArgumentException | IllegalStateException ignored) { }
            }
        }
        while (!candidates.isEmpty()) {
            TailCandidate selected = candidates.remove(random.nextInt(candidates.size()));
            return new TailTemplate(selected.steps(), selected.multiplier());
        }
        return null;
    }

    private List<Bucket> lossBuckets(int betType, GameRuleCore.CollectionState collectionState) throws IOException {
        List<String> eligible = eligibleMembers(false, 0, ClaimPurpose.PAID, betType, collectionState);
        return eligible.isEmpty() ? List.of() : List.of(new Bucket(false, 0, eligible));
    }

    private List<Bucket> paidWinningBuckets(int betType, GameRuleCore.CollectionState collectionState) throws IOException {
        List<Bucket> result = new ArrayList<>();
        result.addAll(buckets(false, true, ClaimPurpose.PAID, betType, collectionState));
        result.addAll(buckets(true, true, ClaimPurpose.PAID, betType, collectionState));
        List<Bucket> weighted = new ArrayList<>(result);
        for (Bucket bucket : result) {
            if (!containsCoinReward(bucket)) continue;
            // 金币满槽是5000个原厂付费起点中仅22次的稀有分支。这里只放大“已存在且
            // 与当前会话前态相邻合法”的实际倍率桶；不生成牌、不改倍率、不突破上限。
            // 新局仍先随机中/不中，再在对应池中随机倍率，最后原子领取一个完整member。
            for (int copy = 1; copy < COIN_REWARD_BUCKET_WEIGHT; copy++) weighted.add(bucket);
        }
        return weighted;
    }

    private boolean containsCoinReward(Bucket bucket) {
        for (String member : bucket.eligibleMembers()) {
            if (codec.verify(member).roundClass() == GameRuleCore.RoundClass.COIN_COLLECTION_REWARD) return true;
        }
        return false;
    }

    private List<Bucket> buckets(boolean special, boolean positiveOnly, ClaimPurpose purpose, int betType,
                                 GameRuleCore.CollectionState collectionState) throws IOException {
        String index = special ? RedisKeyContract.specialIndex(GAME_ID) : RedisKeyContract.normalIndex(GAME_ID);
        List<Bucket> result = new ArrayList<>();
        for (Object item : asList(redis.command("ZRANGE", index, "0", "-1"))) {
            int multiplier = Integer.parseInt(String.valueOf(item));
            if (positiveOnly && multiplier <= 0) continue;
            List<String> eligible = eligibleMembers(special, multiplier, purpose, betType, collectionState);
            if (!eligible.isEmpty()) result.add(new Bucket(special, multiplier, eligible));
        }
        return result;
    }

    private List<String> eligibleMembers(boolean special, int multiplier, ClaimPurpose purpose, int betType,
                                         GameRuleCore.CollectionState collectionState) throws IOException {
        String key = special ? RedisKeyContract.specialList(GAME_ID, multiplier) : RedisKeyContract.normalList(GAME_ID, multiplier);
        List<String> result = new ArrayList<>();
        for (Object item : asList(redis.command("LRANGE", key, "0", "-1"))) {
            String member = String.valueOf(item);
            try {
                ClaimedRound checked = verify(member, special, multiplier, purpose);
                if (purpose == ClaimPurpose.FEATURE_BUY
                        && checked.fact().entryKind() == GameRuleCore.EntryKind.FEATURE_BUY_INITIAL
                        && GameRuleCore.canApplyCollectionTransition(collectionState, checked.fact())) result.add(member);
                if (purpose == ClaimPurpose.PAID
                        && checked.fact().entryKind() == GameRuleCore.EntryKind.PAID_INITIAL
                        && checked.fact().betType() == betType
                        && GameRuleCore.canApplyCollectionTransition(collectionState, checked.fact())) result.add(member);
            } catch (IllegalArgumentException | IllegalStateException invalid) {
                // 非本入口 member 留在原池；例如购买不能拿自然 Free 或金币局。
            }
        }
        return result;
    }

    private ClaimedRound claimFromBuckets(SecureRandom random, List<Bucket> initial, ClaimPurpose purpose) throws IOException {
        List<Bucket> buckets = new ArrayList<>(initial);
        while (!buckets.isEmpty()) {
            Bucket bucket = buckets.remove(random.nextInt(buckets.size()));
            String member;
            List<String> eligible = new ArrayList<>(bucket.eligibleMembers());
            while (!eligible.isEmpty()) {
                member = eligible.remove(random.nextInt(eligible.size()));
                return verify(member, bucket.special(), bucket.multiplier(), purpose);
            }
        }
        throw empty("所选结果类别在领取时已耗尽");
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
    private record TailCandidate(String key, String member, List<GameRuleCore.Step> steps, int multiplier) { }
    private record Bucket(boolean special, int multiplier, List<String> eligibleMembers) { }
}
