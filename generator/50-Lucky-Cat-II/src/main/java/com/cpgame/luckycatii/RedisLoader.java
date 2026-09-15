package com.cpgame.luckycatii;

import com.cpgame.luckycatii.model.ResultAnalysis;
import com.cpgame.luckycatii.model.RoundMode;
import com.cpgame.luckycatii.model.RoundResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pre-generates complete rounds into Redis. The controller never generates. */
public final class RedisLoader {
    private static final BigDecimal BET_SIZE = new BigDecimal("0.1");
    private static final int BET_LEVEL = 1;

    public LoadSummary load(GeneratorConfig config) throws Exception {
        GameRuleCore core = new GameRuleCore();
        RoundVerifier verifier = new RoundVerifier();
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec(new RoundFactory(), verifier);
        List<Member> pending = new ArrayList<>(config.batchSize);
        Counters counters = new Counters();
        try (RedisConnection redis = RedisConnection.connect(config)) {
            clearExistingGameKeys(redis, config.redisGameId);
            for (int i = 0; i < config.outputLimits.lossTarget(config.lossCount); i++)
                if (!accept(core.generateIndependentLoss(BET_SIZE, BET_LEVEL), RoundMode.ORDINARY_LOSS,
                        false, config, verifier, codec, pending, redis, counters)) i--;
            for (int i = 0; i < config.winCount; i++)
                if (!accept(core.generateOrdinaryWin(BET_SIZE, BET_LEVEL), RoundMode.ORDINARY_WIN,
                        false, config, verifier, codec, pending, redis, counters)) i--;
            for (int i = 0; i < config.specialCount; i++)
                if (!accept(core.generateSpecial(BET_SIZE, BET_LEVEL), null,
                        true, config, verifier, codec, pending, redis, counters)) i--;
            flush(redis, pending, config, counters);
        }
        return counters.summary(config.redisGameId);
    }

    private static void clearExistingGameKeys(RedisConnection redis, long gameId) throws Exception {
        List<String> keys = new ArrayList<>();
        keys.add(normalIndex(gameId));
        keys.add(specialIndex(gameId));
        for (String pattern : List.of(
                String.format(Locale.ROOT, "BetLog:0%08d:*", gameId),
                String.format(Locale.ROOT, "MaryLog:%09d:*", gameId))) {
            Object found = redis.command("KEYS", pattern);
            if (found instanceof List<?> list) for (Object key : list) keys.add(key.toString());
        }
        if (!keys.isEmpty()) redis.command(java.util.stream.Stream.concat(
                java.util.stream.Stream.of("DEL"), keys.stream()).toArray(String[]::new));
    }

    private static boolean accept(RoundResult round, RoundMode expected, boolean special, GeneratorConfig config,
                               RoundVerifier verifier, MinimalRoundFactCodec codec, List<Member> pending,
                               RedisConnection redis, Counters counters) throws Exception {
        LoaderLimits.checkAttempts(++counters.candidates, (long) config.lossCount + config.winCount + config.specialCount);
        ResultAnalysis analysis = verifier.verify(round);
        if (expected != null && analysis.redisPoolMode() != expected)
            throw new IllegalStateException("生成类别与目标池不一致");
        if (special != ResultUtil.isSpecialPool(analysis))
            throw new IllegalStateException("特殊池标记与 ResultUtil 不一致");
        if (!config.outputLimits.accepts(special, analysis.integerMultiplier())) return false;
        int consecutive = analysis.luckyRespin() ? 2 : (analysis.award().signum() == 0 ? 0 : 1);
        if (consecutive > config.maxConsecutiveWins) return false;
        int ratio = analysis.integerMultiplier();
        if (!config.outputLimits.accepts(special, ratio)) return false;
        String payload = codec.encodeRedisMemberString(round);
        RoundResult rebuilt = codec.decodeRedisMember(payload);
        verifier.verifyRecovery(round, rebuilt);
        if (ResultUtil.analyze(rebuilt).integerMultiplier() != ratio)
            throw new IllegalStateException("member 往返后整数倍率变化");
        pending.add(new Member(special, ratio, payload));
        counters.accept(analysis, ratio, round.steps().size());
        if (pending.size() >= config.batchSize) flush(redis, pending, config, counters);
        return true;
    }

    private static void flush(RedisConnection redis, List<Member> pending,
                              GeneratorConfig config, Counters counters) throws Exception {
        if (pending.isEmpty()) return;
        List<String[]> commands = new ArrayList<>(pending.size() * 3);
        for (Member member : pending) {
            String index = member.special ? specialIndex(config.redisGameId) : normalIndex(config.redisGameId);
            String list = member.special ? specialList(config.redisGameId, member.ratio)
                    : normalList(config.redisGameId, member.ratio);
            String ratio = Integer.toString(member.ratio);
            commands.add(new String[]{"ZADD", index, ratio, ratio});
            commands.add(new String[]{"RPUSH", list, member.payload});
            int cap = member.special ? config.outputLimits.specialCap : config.maxMembersPerMultiplier;
            commands.add(new String[]{"LTRIM", list, "-" + cap, "-1"});
        }
        redis.transaction(commands);
        counters.batches++;
        pending.clear();
    }

    public static String normalIndex(long id) { return String.format(Locale.ROOT, "PerKeyList_%09d", id); }
    public static String specialIndex(long id) { return String.format(Locale.ROOT, "MaryKeyList_%09d", id); }
    public static String normalList(long id, int ratio) {
        return String.format(Locale.ROOT, "BetLog:0%08d:%06d", id, ratio);
    }
    public static String specialList(long id, int ratio) {
        return String.format(Locale.ROOT, "MaryLog:%09d:%06d", id, ratio);
    }

    private record Member(boolean special, int ratio, String payload) {}

    private static final class Counters {
        int loss, win, special, batches, maxDeliveries, lucky, wheel;
        long candidates;
        final Map<Integer, Integer> normalDistribution = new LinkedHashMap<>();
        final Map<Integer, Integer> specialDistribution = new LinkedHashMap<>();
        void accept(ResultAnalysis analysis, int multiplier, int deliveries) {
            if (ResultUtil.isSpecialPool(analysis)) {
                special++;
                if (analysis.luckyRespin()) lucky++;
                if (analysis.wheel()) wheel++;
                specialDistribution.merge(multiplier, 1, Integer::sum);
            } else if (analysis.redisPoolMode() == RoundMode.ORDINARY_LOSS) {
                loss++;
                normalDistribution.merge(multiplier, 1, Integer::sum);
            } else {
                win++;
                normalDistribution.merge(multiplier, 1, Integer::sum);
            }
            maxDeliveries = Math.max(maxDeliveries, deliveries);
        }
        LoadSummary summary(long gameId) {
            return new LoadSummary(gameId, loss, win, special, lucky, wheel, batches, candidates, maxDeliveries,
                    Map.copyOf(normalDistribution), Map.copyOf(specialDistribution));
        }
    }

    public record LoadSummary(long redisGameId, int lossMembers, int winMembers, int specialMembers,
                              int luckyMembers, int wheelMembers, int batches, long candidates, int maxDeliveries,
                              Map<Integer, Integer> normalDistribution,
                              Map<Integer, Integer> specialDistribution) {}
}
