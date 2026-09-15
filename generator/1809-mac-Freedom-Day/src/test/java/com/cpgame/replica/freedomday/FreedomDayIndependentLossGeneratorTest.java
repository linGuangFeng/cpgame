package com.cpgame.replica.freedomday;

import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayBoard;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayIndependentLossGenerator;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayBoardGenerator;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayOrdinaryLossPolicy;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class FreedomDayIndependentLossGeneratorTest {
    @Test void producesOnlyIndependentZeroWinBoards() {
        FreedomDayIndependentLossGenerator generator = new FreedomDayIndependentLossGenerator();
        Random random = new Random(1809L);
        for (int i = 0; i < 1_000; i++) {
            FreedomDayBoard board = generator.generate(random, (i & 1) == 1);
            assertTrue(FreedomDayIndependentLossGenerator.isIndependentLoss(board));
        }
    }

    @Test void constrainedCandidateAlwaysSucceedsOnFirstAttemptWithoutFeatureTrigger() {
        com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayBoardGenerator boards =
                new com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayBoardGenerator(new Random(2260L));
        int samples = 100_000;
        int successes = 0;
        for (int i = 0; i < samples; i++) {
            FreedomDayBoard board = boards.generateIndependentLossCandidate((i & 1) == 1);
            if (FreedomDayIndependentLossGenerator.isIndependentLoss(board)) successes++;
            assertTrue(scatterCount(board) < 4);
        }
        double successRate = successes / (double) samples;
        assertTrue(successRate >= FreedomDayIndependentLossGenerator.REQUIRED_FIRST_ATTEMPT_SUCCESS_RATE,
                "first-attempt success rate=" + successRate);
        assertEquals(samples, successes, "Freedom Day constrained Ways construction should currently be 100%");
        assertEquals(5, FreedomDayIndependentLossGenerator.RANDOM_ATTEMPTS);
    }

    @Test void degenerateRandomStillProducesLossWithoutLooping() {
        FreedomDayIndependentLossGenerator generator = new FreedomDayIndependentLossGenerator();
        CountingZeroRandom random = new CountingZeroRandom();
        FreedomDayBoard board = generator.generate(random, false);
        assertTrue(FreedomDayIndependentLossGenerator.isIndependentLoss(board));
        assertTrue(random.calls > 0);
    }

    @Test void ordinaryLossBallOccurrenceIsHalvedAcrossOneHundredThousandIndependentSamples() {
        int samples = 100_000;
        FreedomDayIndependentLossGenerator generator = new FreedomDayIndependentLossGenerator();
        Random baselineRandom = new Random(1809001L);
        Random modifiedRandom = new Random(1809002L);
        int baselineWithBall = 0;
        int modifiedWithBall = 0;
        int[] normal = FreedomDayBoardGenerator.defaultNormalWeights();
        int[] mary = FreedomDayBoardGenerator.defaultFreeWeights();
        for (int i = 0; i < samples; i++) {
            FreedomDayBoard baseline = generator.generate(baselineRandom, false);
            assertTrue(FreedomDayIndependentLossGenerator.isIndependentLoss(baseline));
            if (FreedomDayOrdinaryLossPolicy.containsBall(baseline)) baselineWithBall++;

            FreedomDayBoard raw = generator.generate(modifiedRandom, false);
            FreedomDayBoard adjusted = FreedomDayOrdinaryLossPolicy.halveBallOccurrence(
                    raw, modifiedRandom, normal, mary);
            assertTrue(FreedomDayIndependentLossGenerator.isIndependentLoss(adjusted));
            if (FreedomDayOrdinaryLossPolicy.containsBall(adjusted)) modifiedWithBall++;
        }
        double baselineRate = baselineWithBall / (double) samples;
        double modifiedRate = modifiedWithBall / (double) samples;
        double expected = baselineRate / 2.0d;
        double combinedSe = Math.sqrt(baselineRate * (1 - baselineRate) / samples / 4.0
                + modifiedRate * (1 - modifiedRate) / samples);
        System.out.printf("LOSS_BALL_DISTRIBUTION samples=%d baseline=%d rate=%.8f modified=%d rate=%.8f target=%.8f se=%.8f%n",
                samples, baselineWithBall, baselineRate, modifiedWithBall, modifiedRate, expected, combinedSe);
        assertEquals(expected, modifiedRate, Math.max(5 * combinedSe, 0.003),
                "modified occurrence must be statistically consistent with exactly 50% of baseline");
    }

    @Test void configuredWeightsControlNormalAndMarySymbols() {
        int[] normal = new int[13]; normal[0] = 1;
        int[] mary = new int[13]; mary[11] = 1;
        FreedomDayBoardGenerator generator = new FreedomDayBoardGenerator(new Random(1L), normal, mary);
        assertTrue(java.util.Arrays.stream(generator.generate(false).getProp()).allMatch(v -> v == 1));
        assertTrue(java.util.Arrays.stream(generator.generate(true).getProp()).allMatch(v -> v == 12));
    }

    private static int scatterCount(FreedomDayBoard board) {
        int count = 0;
        for (int symbol : board.getProp()) if (symbol == 12) count++;
        for (int symbol : board.getTrl()) if (symbol == 12) count++;
        return count;
    }

    private static final class CountingZeroRandom extends Random {
        int calls;
        @Override public int nextInt(int bound) { calls++; return 0; }
    }
}
