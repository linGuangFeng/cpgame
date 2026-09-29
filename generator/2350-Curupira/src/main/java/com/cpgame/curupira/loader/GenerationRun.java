package com.cpgame.curupira.loader;

import com.cpgame.curupira.codec.MinimalFactCodec;
import com.cpgame.curupira.config.EngineConfiguration;
import com.cpgame.curupira.core.GameRuleCore;
import com.cpgame.curupira.core.GenerationScene;
import com.cpgame.curupira.core.GenerationPolicy;
import com.cpgame.curupira.core.ResultUtil;
import com.cpgame.curupira.model.CompleteRoundFact;
import com.cpgame.curupira.model.CompleteRoundFact.Kind;
import com.cpgame.curupira.random.RandomSource;
import com.cpgame.curupira.redis.RedisContractGate;
import com.cpgame.curupira.redis.RedisListClient;
import com.cpgame.curupira.verify.RoundVerifier;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 正式生成循环；尝试次数、批次相位、分类与拒绝都在这一条路径内完成。 */
public final class GenerationRun {
    public enum RejectionReason {
        BELOW_CONFIGURED_RANGE,
        ABOVE_CONFIGURED_RANGE
    }

    @FunctionalInterface
    public interface BatchSink {
        void append(int batchNumber, int phase, List<RedisListClient.Entry> entries) throws Exception;
    }

    private GenerationRun() { }

    public static Summary execute(EngineConfiguration config, RandomSource random, BatchSink sink) throws Exception {
        MinimalFactCodec codec = new MinimalFactCodec();
        RoundVerifier verifier = new RoundVerifier();
        ResultUtil resultUtil = new ResultUtil();
        RedisContractGate keys = new RedisContractGate();
        Map<Kind, Long> acceptedByKind = new EnumMap<>(Kind.class);
        Map<GenerationScene, Long> attemptsByScene = new EnumMap<>(GenerationScene.class);
        Map<RejectionReason, Long> rejectedByReason = new EnumMap<>(RejectionReason.class);
        Map<Integer, Long> multiplierFrequency = new LinkedHashMap<>();
        Map<GenerationScene, Map<Integer, Long>> multiplierFrequencyByScene = new EnumMap<>(GenerationScene.class);
        Map<Integer, Long> phaseAttempts = new LinkedHashMap<>();
        long attempts = 0;
        long accepted = 0;
        int batchNumber = 0;

        while (attempts < config.generationCount()) {
            int phase = batchNumber % config.cycleLength();
            int currentBatch = (int) Math.min(config.batchSize(), config.generationCount() - attempts);
            GameRuleCore core = new GameRuleCore(random, new GenerationPolicy(config.effectiveWeights(phase)),
                    config.holdEmptyWeight(), config.holdCoinWeight());
            List<RedisListClient.Entry> entries = new ArrayList<>(currentBatch);
            for (int index = 0; index < currentBatch; index++) {
                attempts++;
                phaseAttempts.merge(phase, 1L, Long::sum);
                GenerationScene scene = config.chooseScene(random);
                attemptsByScene.merge(scene, 1L, Long::sum);
                CompleteRoundFact fact = switch (scene) {
                    case NORMAL_PAID -> core.generatePaidCandidate();
                    case FREE_EXPANDING_WILD -> core.generateFreeExpandingWildCandidate();
                    case HOLD_AND_SPINS -> core.generateHoldAndSpinsCandidate();
                };
                int multiplier = resultUtil.redisMultiplier(fact);
                int minimum = scene == GenerationScene.NORMAL_PAID
                        ? config.normalMinWinMultiplier() : config.maryMinWinMultiplier();
                int maximum = scene == GenerationScene.NORMAL_PAID
                        ? config.normalMaxWinMultiplier() : config.maryMaxWinMultiplier();
                if (multiplier < minimum) {
                    rejectedByReason.merge(RejectionReason.BELOW_CONFIGURED_RANGE, 1L, Long::sum);
                    continue;
                }
                if (multiplier > maximum) {
                    rejectedByReason.merge(RejectionReason.ABOVE_CONFIGURED_RANGE, 1L, Long::sum);
                    continue;
                }
                String member = codec.encodeFact(fact);
                CompleteRoundFact decoded = codec.decode(member);
                verifier.verifyFact(decoded);
                if (decoded.kind() != fact.kind() || !decoded.steps().equals(fact.steps())
                        || resultUtil.redisMultiplier(decoded) != multiplier) {
                    throw new IllegalStateException("Codec 回读完整局事实不一致");
                }
                entries.add(new RedisListClient.Entry(
                        keys.indexesToWrite(fact.kind(), config.redisGameId()),
                        keys.listFor(fact.kind(), multiplier, config.redisGameId()),
                        multiplier, member));
                accepted++;
                acceptedByKind.merge(fact.kind(), 1L, Long::sum);
                multiplierFrequency.merge(multiplier, 1L, Long::sum);
                multiplierFrequencyByScene.computeIfAbsent(scene, ignored -> new LinkedHashMap<>())
                        .merge(multiplier, 1L, Long::sum);
            }
            sink.append(batchNumber, phase, List.copyOf(entries));
            batchNumber++;
        }

        long rejected = rejectedByReason.values().stream().mapToLong(Long::longValue).sum();
        if (accepted + rejected != attempts) throw new IllegalStateException("生成计数不守恒");
        return new Summary(attempts, accepted, rejected, batchNumber,
                Map.copyOf(attemptsByScene), Map.copyOf(acceptedByKind), Map.copyOf(rejectedByReason),
                Map.copyOf(multiplierFrequency), immutableNested(multiplierFrequencyByScene),
                Map.copyOf(phaseAttempts));
    }

    private static Map<GenerationScene, Map<Integer, Long>> immutableNested(
            Map<GenerationScene, Map<Integer, Long>> values) {
        Map<GenerationScene, Map<Integer, Long>> result = new EnumMap<>(GenerationScene.class);
        values.forEach((scene, frequencies) -> result.put(scene, Map.copyOf(frequencies)));
        return Map.copyOf(result);
    }

    public record Summary(
            long attempts,
            long accepted,
            long rejected,
            int batches,
            Map<GenerationScene, Long> attemptsByScene,
            Map<Kind, Long> acceptedByKind,
            Map<RejectionReason, Long> rejectedByReason,
            Map<Integer, Long> multiplierFrequency,
            Map<GenerationScene, Map<Integer, Long>> multiplierFrequencyByScene,
            Map<Integer, Long> phaseAttempts) { }
}
