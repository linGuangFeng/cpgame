package com.cpgame.christmasgift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.security.SecureRandom;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Emits an independently inspectable distribution summary from the real Java generation path. */
final class GenerationDistributionTest {
    @Test void emitTenThousandRoundDistribution() {
        var model = new GenerationModel(new SecureRandom(), new int[]{100,100,100,100,100,100,100});
        var factory = new RoundFactory(model);
        var verifier = new RoundVerifier();
        Map<Integer,Long> ordinarySymbols = new TreeMap<>();
        Map<Integer,Long> featureSteps = new TreeMap<>();
        Map<Integer,Long> featureTargets = new TreeMap<>();
        Map<Integer,Long> featureInitialSizes = new TreeMap<>();
        Map<Integer,Long> featureIncrementSizes = new TreeMap<>();
        Map<String,Long> outcomes = new TreeMap<>();
        long fullScreens = 0;

        for (int index = 0; index < 10_000; index++) {
            GameRuleCore.CompleteRound round = index < 9_700
                ? factory.ordinary() : factory.christmasGiftFeature();
            verifier.verify(round);
            var evaluation = ResultUtil.evaluate(round);
            if (round.mode() == GameRuleCore.Mode.ORDINARY) {
                outcomes.merge(evaluation.outcome().name(), 1L, Long::sum);
                round.steps().get(0).newlyDealt().values()
                    .forEach(symbol -> ordinarySymbols.merge(symbol, 1L, Long::sum));
            } else {
                outcomes.merge("FEATURE", 1L, Long::sum);
                featureSteps.merge(round.steps().size(), 1L, Long::sum);
                featureTargets.merge(round.targetSymbol(), 1L, Long::sum);
                featureInitialSizes.merge(positiveCount(round.steps().get(0)), 1L, Long::sum);
                for (int step = 1; step < round.steps().size(); step++) {
                    int size = positiveCount(round.steps().get(step));
                    if (size > 0) featureIncrementSizes.merge(size, 1L, Long::sum);
                }
                if (evaluation.bigWin()) fullScreens++;
            }
        }
        assertEquals(10_000L, outcomes.values().stream().mapToLong(Long::longValue).sum());
        System.out.println("GENERATION_DISTRIBUTION ordinarySymbols=" + ordinarySymbols
            + " featureSteps=" + featureSteps + " featureTargets=" + featureTargets
            + " featureInitialSizes=" + featureInitialSizes
            + " featureIncrementSizes=" + featureIncrementSizes
            + " outcomes=" + outcomes + " fullScreens=" + fullScreens);
    }

    private int positiveCount(GameRuleCore.Deal deal) {
        return (int) deal.newlyDealt().values().stream().filter(value -> value > 0).count();
    }
}
