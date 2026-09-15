package com.cpgame.batchc.cybergo;

import static com.cpgame.batchc.cybergo.CyberGoRules.MINIMUM_BET;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SecureRandom自然生成完整Round，ResultUtil独立反推后直接写入平台Redis。 */
public final class RedisLoader {
    public LoadSummary load(GeneratorConfig config) throws Exception {
        GameRuleCore core = new GameRuleCore(new SecureRandom(), config.limits());
        MinimalFactCodec codec = new MinimalFactCodec();
        List<Member> pending = new ArrayList<>(config.batchSize);
        Counters counters = new Counters();
        try (RedisConnection redis = RedisConnection.connect(config)) {
            while (counters.normal < config.normalCount || counters.special < config.specialCount || counters.loss < config.outputLimits.lossTarget(config.lossCount)) {
                LoaderLimits.checkAttempts(++counters.candidates, (long) config.normalCount + config.specialCount + config.lossCount);
                CyberGoModels.CompleteRound round = core.generateCompleteRound();
                ResultUtil.RoundResult result = ResultUtil.reverse(round);
                boolean special = result.inferredKind() == CyberGoModels.RoundKind.FREE_SPINS;
                boolean loss = result.totalWin().signum() == 0;
                if ((loss && counters.loss >= config.lossCount) || (!loss && special && counters.special >= config.specialCount)
                        || (!loss && !special && counters.normal >= config.normalCount)) continue;

                BigDecimal multiplier = result.totalWin().divide(MINIMUM_BET, 8, RoundingMode.HALF_UP)
                        .stripTrailingZeros();
                if (multiplier.stripTrailingZeros().scale() > 0) {
                    counters.zeroSkipped++;
                    continue;
                }
                if (!config.outputLimits.accepts(special, multiplier)) continue;
                BigDecimal maximum = special ? config.specialMaxWinMultiplier : config.normalMaxWinMultiplier;
                int consecutiveWins = maximumConsecutiveWins(round);
                if (multiplier.compareTo(maximum) > 0 || consecutiveWins > config.maxConsecutiveWins) {
                    counters.limitSkipped++;
                    continue;
                }

                CyberGoModels.MinimalRoundFacts facts = codec.extract(round);
                byte[] encoded = codec.encodeForRedis(facts);
                CyberGoModels.CompleteRound rebuilt = core.rebuild(codec.decodeFromRedis(encoded));
                ResultUtil.RoundResult rebuiltResult = ResultUtil.reverse(rebuilt);
                BigDecimal rebuiltMultiplier = rebuiltResult.totalWin().divide(MINIMUM_BET, 8, RoundingMode.HALF_UP)
                        .stripTrailingZeros();
                if (result.inferredKind() != rebuiltResult.inferredKind()
                        || multiplier.compareTo(rebuiltMultiplier) != 0
                        || round.deliveries().size() != rebuilt.deliveries().size())
                    throw new IllegalStateException("Redis member往返后的模式、倍率或Delivery数量不一致");

                pending.add(new Member(special, multiplier.toPlainString(),
                        new String(encoded, StandardCharsets.US_ASCII)));
                if (loss) counters.loss++; else if (special) counters.special++; else counters.normal++;
                counters.accept(special, multiplier.toPlainString(), round.deliveries().size());
                if (pending.size() >= config.batchSize) flush(redis, pending, config, counters);
            }
            flush(redis, pending, config, counters);
        }
        System.out.println("LOSS_PREWRITTEN="+counters.loss);
        return counters.summary(config.redisGameId);
    }

    private static int maximumConsecutiveWins(CyberGoModels.CompleteRound round) {
        int maximum = 0, current = 0;
        for (CyberGoModels.Step step : round.deliveries()) {
            if (step.wa().signum() > 0) { current++; maximum = Math.max(maximum, current); }
            else current = 0;
        }
        return maximum;
    }

    private static void flush(RedisConnection redis, List<Member> pending,
                              GeneratorConfig config, Counters counters) throws Exception {
        if (pending.isEmpty()) return;
        List<String[]> commands = new ArrayList<>(pending.size() * 3);
        for (Member member : pending) {
            String index = member.special ? RedisRoundPool.SPECIAL_INDEX : RedisRoundPool.INDEX;
            String list = member.special ? RedisRoundPool.specialList(member.multiplier) : RedisRoundPool.list(member.multiplier);
            int cap = member.special ? config.outputLimits.specialCap : config.maxMembersPerMultiplier;
            commands.add(new String[]{"ZADD", index, member.multiplier, member.multiplier});
            commands.add(new String[]{"RPUSH", list, member.payload});
            commands.add(new String[]{"LTRIM", list, "-" + cap, "-1"});
        }
        redis.transaction(commands); // RedisConnection在同一MULTI/EXEC中执行全部命令。
        counters.batches++;
        pending.clear();
    }

    private record Member(boolean special, String multiplier, String payload) { }

    private static final class Counters {
        int loss, normal, special, batches, maxDeliveries;
        long candidates, zeroSkipped, limitSkipped;
        final Map<String, Integer> normalDistribution = new LinkedHashMap<>();
        final Map<String, Integer> specialDistribution = new LinkedHashMap<>();

        void accept(boolean specialMode, String multiplier, int deliveries) {
            (specialMode ? specialDistribution : normalDistribution).merge(multiplier, 1, Integer::sum);
            maxDeliveries = Math.max(maxDeliveries, deliveries);
        }

        LoadSummary summary(long gameId) {
            return new LoadSummary(gameId, normal, special, batches, candidates, zeroSkipped, limitSkipped,
                    maxDeliveries, Map.copyOf(normalDistribution), Map.copyOf(specialDistribution));
        }
    }

    public record LoadSummary(long redisGameId, int normalMembers, int specialMembers, int batches,
                              long candidates, long zeroSkipped, long limitSkipped, int maxDeliveries,
                              Map<String, Integer> normalDistribution,
                              Map<String, Integer> specialDistribution) { }
}
