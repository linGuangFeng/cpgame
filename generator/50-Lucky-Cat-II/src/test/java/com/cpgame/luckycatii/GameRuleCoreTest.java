package com.cpgame.luckycatii;

import com.cpgame.luckycatii.model.ResultAnalysis;
import com.cpgame.luckycatii.model.RoundMode;
import com.cpgame.luckycatii.model.RoundResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class GameRuleCoreTest {
    private static final BigDecimal BS = new BigDecimal("0.1");
    private final GameRuleCore core = new GameRuleCore();
    private final RoundVerifier verifier = new RoundVerifier();

    @Test void batchLossNeverWinsAndStaysOrdinary() {
        Set<String> keys = new HashSet<>();
        Set<Object> boards = new HashSet<>();
        var rng = new SplittableRandom(50001);
        for (int i = 0; i < 200; i++) {
            RoundResult round = core.generateIndependentLoss(BS, 1, rng);
            ResultAnalysis inferred = verifier.verify(round);
            assertEquals(RoundMode.ORDINARY_LOSS, inferred.redisPoolMode());
            assertEquals(0, round.award().signum());
            assertEquals(0, round.gameMode());
            assertEquals(1, round.rpx());
            assertNull(ResultUtil.findLuckyTrigger(round.finalBoard()));
            assertTrue(keys.add(round.roundKey()));
            boards.add(round.finalBoard());
        }
        assertTrue(boards.size() > 80);
    }

    @Test void firstLossCandidateIsAlwaysAMiss() {
        IndependentLossGenerator generator = new IndependentLossGenerator();
        var rng = new SplittableRandom(50000);
        int stacked = 0;
        for (int i = 0; i < 2000; i++) {
            var candidate = generator.candidate(rng);
            assertTrue(IndependentLossGenerator.isIndependentLoss(candidate));
            if (uniformReels(candidate.finalBoard())) stacked++;
        }
        assertTrue(stacked < 1600, "loss boards should not be only AAA|BBB|CCC stacked=" + stacked);
    }

    @Test void batchOrdinaryWinHasPaylinesWithoutSpecialModes() {
        var rng = new SplittableRandom(50002);
        int min = Integer.MAX_VALUE;
        int max = 0;
        int small = 0, mid = 0, high = 0;
        Set<Integer> buckets = new HashSet<>();
        for (int i = 0; i < 400; i++) {
            RoundResult round = core.generateOrdinaryWin(BS, 1, rng);
            ResultAnalysis inferred = verifier.verify(round);
            assertEquals(RoundMode.ORDINARY_WIN, inferred.redisPoolMode());
            assertTrue(round.award().signum() > 0);
            assertEquals(0, round.gameMode());
            assertEquals(1, round.rpx());
            assertFalse(inferred.luckyRespin());
            assertFalse(inferred.wheel());
            int units = inferred.integerMultiplier();
            assertTrue(units >= IndependentWin.MIN_UNITS && units <= IndependentWin.MAX_UNITS, "ordinary units=" + units);
            buckets.add(units);
            min = Math.min(min, units);
            max = Math.max(max, units);
            if (units <= 25) small++;
            else if (units <= 100) mid++;
            else high++;
        }
        assertTrue(min <= 7, "smallest ordinary was " + min);
        assertTrue(max >= 80, "largest ordinary was " + max);
        assertTrue(small > 40, "small ordinary count=" + small);
        assertTrue(mid > 40, "mid ordinary count=" + mid);
        assertTrue(high > 40, "high ordinary count=" + high);
        assertTrue(buckets.size() >= 8, "ordinary buckets=" + buckets);
    }

    @Test void batchLuckyAndWheelAreCompleteEmbeddedRounds() {
        var rng = new SplittableRandom(50003);
        int lucky = 0, wheel = 0, highWheel = 0;
        int minSpecial = Integer.MAX_VALUE;
        for (int i = 0; i < 80; i++) {
            RoundResult luckyRound = core.generateLuckyRespin(BS, 1, rng);
            ResultAnalysis luckyAnalysis = verifier.verify(luckyRound);
            assertTrue(luckyAnalysis.luckyRespin());
            assertEquals(1, luckyRound.gameMode());
            assertEquals(2, luckyRound.steps().size());
            assertFalse(luckyRound.steps().get(1).paid());
            int luckyUnits = luckyAnalysis.integerMultiplier();
            assertTrue(luckyUnits >= IndependentSpecial.MIN_UNITS && luckyUnits <= IndependentSpecial.MAX_UNITS);
            minSpecial = Math.min(minSpecial, luckyUnits);
            lucky++;
            RoundResult wheelRound = core.generateMultiplierWheel(BS, 1, rng);
            ResultAnalysis wheelAnalysis = verifier.verify(wheelRound);
            assertTrue(wheelAnalysis.wheel());
            assertTrue(GameRules.CONFIRMED_WHEEL_MULTIPLIERS.contains(wheelRound.rpx()));
            int wheelUnits = wheelAnalysis.integerMultiplier();
            assertTrue(wheelUnits >= IndependentSpecial.MIN_UNITS && wheelUnits <= IndependentSpecial.MAX_UNITS);
            minSpecial = Math.min(minSpecial, wheelUnits);
            if (wheelUnits > 200) highWheel++;
            wheel++;
        }
        assertEquals(80, lucky);
        assertEquals(80, wheel);
        assertTrue(minSpecial <= 75, "smallest special was " + minSpecial);
        assertTrue(highWheel > 0, "wheel units should cover the paytable above 200");
    }

    @Test void asciiMemberRoundTripsAndRestoreOnlyCoreCannotGenerate() {
        GameRuleCore deterministic = GameRuleCore.forTesting(50004);
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec(new RoundFactory(), verifier);
        for (int i = 0; i < 90; i++) {
            RoundResult round = switch (i % 3) {
                case 0 -> deterministic.generateIndependentLoss(BS, 1);
                case 1 -> deterministic.generateOrdinaryWin(BS, 1);
                default -> deterministic.generateSpecial(BS, 1);
            };
            String member = codec.encodeRedisMemberString(round);
            RoundResult rebuilt = codec.decodeRedisMember(member);
            if ("#".equals(member)) {
                assertEquals(RoundMode.ORDINARY_LOSS, ResultUtil.analyze(rebuilt).redisPoolMode());
                assertEquals(0, rebuilt.award().signum());
            } else verifier.verifyRecovery(round, rebuilt);
            assertFalse(member.contains("{"));
        }
        GameRuleCore restoreOnly = GameRuleCore.forRestoration();
        assertThrows(IllegalStateException.class, () -> restoreOnly.generateOrdinaryWin(BS, 1));
    }

    @Test void reelPatternWeightsShapeDealtReels() {
        IndependentLossGenerator base = new IndependentLossGenerator(
                SymbolWeights.empiricalDefaults(), ReelPatterns.defaults());
        IndependentLossGenerator abcHeavy = new IndependentLossGenerator(
                SymbolWeights.empiricalDefaults(), new ReelPatterns(2, 2, 2, 20));
        int[] baseCounts = new int[4];
        int[] heavyCounts = new int[4];
        var baseRng = new SplittableRandom(50200);
        var heavyRng = new SplittableRandom(50200);
        for (int i = 0; i < 400; i++) {
            countShapes(base.candidate(baseRng).finalBoard(), baseCounts);
            countShapes(abcHeavy.candidate(heavyRng).finalBoard(), heavyCounts);
        }
        int baseAbc = baseCounts[ReelPatterns.Shape.ABC.ordinal()];
        int heavyAbc = heavyCounts[ReelPatterns.Shape.ABC.ordinal()];
        assertTrue(baseCounts[ReelPatterns.Shape.AAB.ordinal()] > 150, "AAB=" + baseCounts[0]);
        assertTrue(baseCounts[ReelPatterns.Shape.BAA.ordinal()] > 150, "BAA=" + baseCounts[1]);
        assertTrue(baseCounts[ReelPatterns.Shape.AAA.ordinal()] > 150, "AAA=" + baseCounts[2]);
        assertTrue(baseAbc > 50 && baseAbc < baseCounts[ReelPatterns.Shape.AAB.ordinal()], "ABC=" + baseAbc);
        assertTrue(heavyAbc > baseAbc * 3 / 2, baseAbc + " -> " + heavyAbc);
    }

    @Test void configuredSymbolCountsChangeDealtBoards() {
        SymbolWeights base = SymbolWeights.empiricalDefaults();
        Map<String, Integer> boostedNormal = new LinkedHashMap<>(base.normal());
        boostedNormal.compute("S1", (key, value) -> value * 20);
        IndependentWin normal = new IndependentWin(base);
        IndependentWin boosted = new IndependentWin(new SymbolWeights(boostedNormal, base.special(), base.respin()));
        int normalS1 = 0, boostedS1 = 0;
        var normalRandom = new SplittableRandom(50100);
        var boostedRandom = new SplittableRandom(50100);
        for (int i = 0; i < 1000; i++) {
            normalS1 += java.util.Collections.frequency(normal.generate(normalRandom).paidBoard(), "S1");
            boostedS1 += java.util.Collections.frequency(boosted.generate(boostedRandom).paidBoard(), "S1");
        }
        assertTrue(boostedS1 > normalS1 * 3 / 2, normalS1 + " -> " + boostedS1);
    }

    private static boolean uniformReels(List<String> board) {
        for (int reel = 0; reel < 3; reel++) {
            String a = board.get(GameRules.cellIndex(reel, 0));
            if (!a.equals(board.get(GameRules.cellIndex(reel, 1)))
                    || !a.equals(board.get(GameRules.cellIndex(reel, 2)))) return false;
        }
        return true;
    }

    private static void countShapes(List<String> board, int[] counts) {
        for (int reel = 0; reel < 3; reel++) counts[ReelPatterns.classifyReel(board, reel).ordinal()]++;
    }
}
