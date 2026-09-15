package com.cpgame.coinmastergo.generator;

import com.cpgame.coinmastergo.core.GameRuleCore;
import com.cpgame.coinmastergo.model.RoundPlan;
import com.cpgame.coinmastergo.service.GameProperties;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** SecureRandom 带权自然生成完整 Round，经独立 ResultUtil 反推后直接写入平台 Redis。 */
public final class RedisDirectLoader {
    static final int ENTRY_SWITCH_EVERY = 1000;
    static final int SPECIAL_TRIGGER_WEIGHT_MULTIPLIER = 10;
    private static final int BET_LEVEL = 1;
    private static final BigDecimal BET_SIZE = new BigDecimal("0.02");

    private RedisDirectLoader() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("用法：java -jar coin-master-go-redis-loader.jar [generator.properties]");
        Path configFile = Path.of(args.length == 0 ? "generator.properties" : args[0]).toAbsolutePath().normalize();
        LoadSummary summary = run(configFile);
        System.out.printf(Locale.ROOT,
                "生成完成 redisGameId=%d 普通候选=%d 特殊候选=%d 普通写入=%d 特殊写入=%d 0倍写入=%d 批次=%d rulesHash=%s%n",
                summary.redisGameId(), summary.normalCandidates(), summary.specialCandidates(),
                summary.normalWritten(), summary.specialWritten(), summary.normalDistribution().getOrDefault("0", 0)
                        + summary.specialDistribution().getOrDefault("0", 0), summary.batches(),
                summary.rulesHash());
    }

    public static LoadSummary run(Path configFile) throws Exception {
        GeneratorConfiguration configuration = GeneratorConfiguration.load(configFile);
        GameRuleCore core = new GameRuleCore(new GameProperties());
        Map<String, Integer> ordinaryOpening = configuration.symbolWeights;
        Map<String, Integer> specialOpening = specialEntryOpeningWeights(ordinaryOpening);
        WeightedGameRuleRandom.install(core, ordinaryOpening,
                configuration.silverCardWeight, configuration.goldCardWeight);
        RoundResultUtil resultUtil = new RoundResultUtil();
        CompleteRoundVerifier verifier = new CompleteRoundVerifier(resultUtil, configuration);
        MinimalFactCodec codec = new MinimalFactCodec();
        Counters counters = new Counters();
        List<RedisListWriter.RedisEntry> pending = new ArrayList<>(configuration.batchSize);
        int drawsInEntry = 0;
        long attempts=0;
        boolean specialEntry = false;

        try (RedisListWriter writer = new JedisRedisListWriter(configuration)) {
            while (counters.normalWritten < configuration.normalCount
                    || counters.specialWritten < configuration.specialCount) {
                LoaderLimits.checkAttempts(++attempts,(long)configuration.normalCount+configuration.specialCount);
                if (drawsInEntry >= ENTRY_SWITCH_EVERY) {
                    specialEntry = !specialEntry;
                    drawsInEntry = 0;
                    core.configureSymbolWeights(specialEntry ? specialOpening : ordinaryOpening);
                }
                RoundPlan round = core.generateRuntimeRound(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                        BET_LEVEL, BET_SIZE, BigDecimal.ZERO, System.currentTimeMillis());
                drawsInEntry++;
                RoundResultUtil.RoundAnalysis analysis = resultUtil.analyze(round);
                boolean special = analysis.special();
                if (special) {
                    if (counters.specialWritten >= configuration.specialCount) continue;
                    counters.specialCandidates++;
                } else {
                    if (counters.normalWritten >= configuration.normalCount) continue;
                    counters.normalCandidates++;
                }
                int ratio = exactRatio(analysis.multiplier());
                if (!shouldPersist(analysis.multiplier())) throw new IllegalStateException("negative multiplier");
                if (ratio < configuration.minimumMultiplier(special)
                        || ratio > configuration.maximumMultiplier(special)
                        || analysis.longestConsecutiveWins() > configuration.maxConsecutiveWins
                        || analysis.freeStepCount() > configuration.maxFreeSpins) {
                    counters.limitSkipped++;
                    continue;
                }

                verifier.verify(round);
                String member = codec.encode(round);
                RoundResultUtil.RoundAnalysis decoded = verifier.verify(codec.rebuild(member));
                if (decoded.special() != special || decoded.pool() != analysis.pool()
                        || decoded.multiplier().compareTo(analysis.multiplier()) != 0
                        || !decoded.rulesHash().equals(core.rulesHash())) {
                    throw new IllegalStateException("member 往返后的模式、倍率或规则哈希不一致");
                }
                String multiplier = Integer.toString(ratio);
                String indexKey = special ? specialIndex(configuration.redisGameId) : normalIndex(configuration.redisGameId);
                String listKey = special ? specialList(configuration.redisGameId, ratio)
                        : normalList(configuration.redisGameId, ratio);
                pending.add(new RedisListWriter.RedisEntry(indexKey, listKey, multiplier, member));
                counters.accept(special, multiplier, analysis.stepCount());
                if (pending.size() >= configuration.batchSize) flush(writer, pending, configuration, counters);
            }
            flush(writer, pending, configuration, counters);
        }
        return counters.summary(configuration.redisGameId, core.rulesHash());
    }

    private static void flush(RedisListWriter writer, List<RedisListWriter.RedisEntry> pending,
                              GeneratorConfiguration configuration, Counters counters) {
        if (pending.isEmpty()) return;
        writer.appendBatchAtomically(pending, configuration.maxMembersPerMultiplier);
        counters.batches++;
        System.out.printf(Locale.ROOT, "批次已提交 batch=%d members=%d%n", counters.batches, pending.size());
        pending.clear();
    }

    static String normalIndex(long id) { return String.format(Locale.ROOT, "PerKeyList_%09d", id); }
    static String specialIndex(long id) { return String.format(Locale.ROOT, "MaryKeyList_%09d", id); }
    static String normalList(long id, int multiplier) {
        return String.format(Locale.ROOT, "BetLog:0%08d:%06d", id, multiplier);
    }
    static String specialList(long id, int multiplier) {
        return String.format(Locale.ROOT, "MaryLog:%09d:%06d", id, multiplier);
    }
    static int exactRatio(BigDecimal value) {
        try { return value.stripTrailingZeros().intValueExact(); }
        catch (ArithmeticException ex) { throw new IllegalStateException("non-integer multiplier: " + value, ex); }
    }
    /** 0倍是规则引擎自然生成的结果；写Redis的取舍固定在代码中，不提供概率或开关配置。 */
    static boolean shouldPersist(BigDecimal value) { return value.signum() >= 0; }

    /** 特殊入口只把付费首局 SC 概率乘 10；连消/免费仍用原倍数。 */
    static Map<String, Integer> specialEntryOpeningWeights(Map<String, Integer> ordinary) {
        LinkedHashMap<String, Integer> boosted = new LinkedHashMap<>(ordinary);
        boosted.put("SC", Math.multiplyExact(ordinary.get("SC"), SPECIAL_TRIGGER_WEIGHT_MULTIPLIER));
        return Map.copyOf(boosted);
    }

    public record LoadSummary(long redisGameId, int normalCandidates, int specialCandidates,
                              int normalWritten, int specialWritten, long zeroSkipped,
                              long limitSkipped, int batches, int maxSteps,
                              Map<String, Integer> normalDistribution,
                              Map<String, Integer> specialDistribution, String rulesHash) { }

    private static final class Counters {
        int normalCandidates, specialCandidates, normalWritten, specialWritten, batches, maxSteps;
        long zeroSkipped, limitSkipped;
        final Map<String, Integer> normalDistribution = new LinkedHashMap<>();
        final Map<String, Integer> specialDistribution = new LinkedHashMap<>();
        void accept(boolean special, String multiplier, int steps) {
            Map<String, Integer> distribution = special ? specialDistribution : normalDistribution;
            distribution.merge(multiplier, 1, Integer::sum);
            if (special) specialWritten++; else normalWritten++;
            maxSteps = Math.max(maxSteps, steps);
        }
        LoadSummary summary(long gameId, String rulesHash) {
            return new LoadSummary(gameId, normalCandidates, specialCandidates, normalWritten, specialWritten,
                    zeroSkipped, limitSkipped, batches, maxSteps, Map.copyOf(normalDistribution),
                    Map.copyOf(specialDistribution), rulesHash);
        }
    }
}
