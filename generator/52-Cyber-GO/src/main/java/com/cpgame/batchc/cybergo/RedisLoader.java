package com.cpgame.batchc.cybergo;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SecureRandom 按规则构造完整 Round，ResultUtil 独立反推后直接写入平台 Redis。 */
public final class RedisLoader {
    public LoadSummary load(GeneratorConfig config) throws Exception {
        GameRuleCore core = new GameRuleCore(new SecureRandom(), config.limits());
        MinimalFactCodec codec = new MinimalFactCodec();
        List<Member> pending = new ArrayList<>(config.batchSize);
        Counters counters = new Counters();
        int lossTarget = config.outputLimits.lossTarget(config.lossCount);
        try (RedisConnection redis = RedisConnection.connect(config)) {
            while (counters.normal < config.normalCount || counters.special < config.specialCount || counters.loss < lossTarget) {
                if (counters.normal < config.normalCount)
                    ingest(core.generateOrdinaryWin(), false, config, core, codec, pending, counters, redis, lossTarget);
                if (counters.special < config.specialCount)
                    ingest(core.generateFreeSpinRound(), true, config, core, codec, pending, counters, redis, lossTarget);
                if (counters.loss < lossTarget)
                    ingest(core.generateOrdinaryLoss(), false, config, core, codec, pending, counters, redis, lossTarget);
            }
            flush(redis, pending, config, counters);
        } catch (java.io.IOException redisError) {
            if (counters.batches > 0 && redisProgressStop(redisError)) {
                System.out.println("[warn] Redis stopped after " + counters.batches
                        + " batches: " + redisError.getMessage());
                pending.clear();
            } else {
                throw redisError;
            }
        }
        System.out.println("LOSS_PREWRITTEN=" + counters.loss);
        return counters.summary(config.redisGameId);
    }

    private static boolean ingest(CyberGoModels.CompleteRound round, boolean expectSpecial, GeneratorConfig config,
                                  GameRuleCore core, MinimalFactCodec codec, List<Member> pending,
                                  Counters counters, RedisConnection redis, int lossTarget) throws Exception {
        LoaderLimits.checkAttempts(++counters.candidates,
                (long) config.normalCount + config.specialCount + config.lossCount);
        ResultUtil.RoundResult result = ResultUtil.reverse(round);
        boolean special = result.inferredKind() == CyberGoModels.RoundKind.FREE_SPINS;
        boolean loss = result.inferredKind() == CyberGoModels.RoundKind.ORDINARY_LOSS;
        if (special != expectSpecial) return false;
        if ((loss && counters.loss >= lossTarget) || (special && counters.special >= config.specialCount)
                || (!loss && !special && counters.normal >= config.normalCount)) return false;

        BigDecimal multiplier = ResultUtil.winMultiplier(round, result);
        if (multiplier.stripTrailingZeros().scale() > 0) {
            counters.zeroSkipped++;
            return false;
        }
        if (!config.outputLimits.accepts(special, multiplier)) return false;
        if (special && result.freeSpinCount() > config.specialMaxSteps) {
            counters.limitSkipped++;
            return false;
        }
        if (maximumConsecutiveWins(round) > config.maxConsecutiveWins) {
            counters.limitSkipped++;
            return false;
        }

        CyberGoModels.MinimalRoundFacts facts = codec.extract(round);
        byte[] encoded = codec.encodeForRedis(facts);
        String payload = new String(encoded, StandardCharsets.US_ASCII);
        CyberGoModels.CompleteRound rebuilt = core.rebuild(codec.decodeFromRedis(encoded));
        ResultUtil.RoundResult rebuiltResult = ResultUtil.reverse(rebuilt);
        BigDecimal rebuiltMultiplier = ResultUtil.winMultiplier(rebuilt, rebuiltResult);
        if ("#".equals(payload)) {
            if (rebuiltResult.inferredKind() != CyberGoModels.RoundKind.ORDINARY_LOSS
                    || rebuiltResult.totalWin().signum() != 0 || rebuilt.deliveries().size() != 1) {
                throw new IllegalStateException("压缩 LOSS 物化后状态不等价");
            }
        } else if (result.inferredKind() != rebuiltResult.inferredKind()
                || multiplier.compareTo(rebuiltMultiplier) != 0
                || round.deliveries().size() != rebuilt.deliveries().size()) {
            throw new IllegalStateException("Redis member往返后的模式、倍率或Delivery数量不一致");
        }

        pending.add(new Member(special, multiplier.toPlainString(), payload));
        if (loss) counters.loss++;
        else if (special) counters.special++;
        else counters.normal++;
        counters.accept(special, multiplier.toPlainString(), round.deliveries().size());
        if (pending.size() >= config.batchSize) flush(redis, pending, config, counters);
        return true;
    }

    private static int maximumConsecutiveWins(CyberGoModels.CompleteRound round) {
        int maximum = 0, current = 0;
        for (CyberGoModels.Step step : round.deliveries()) {
            if (step.wa().signum() > 0) {
                current++;
                maximum = Math.max(maximum, current);
            } else current = 0;
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
        redis.transaction(commands);
        counters.batches++;
        pending.clear();
    }

    static boolean redisProgressStop(Throwable error) {
        String text = error == null ? "" : String.valueOf(error.getMessage());
        if (error != null && error.getCause() != null) text += " " + error.getCause().getMessage();
        return text.contains("OOM") || text.contains("maxmemory") || text.contains("timed out")
                || text.contains("Timed out") || text.contains("MISCONF") || text.contains("Connection reset")
                || text.contains("closed") || text.contains("EXECABORT") || text.contains("Broken pipe")
                || text.contains("已关闭连接") || text.contains("中止了一个已建立");
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
