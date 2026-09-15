package com.cpgame.replica.edmmania;

import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaBoard;
import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaIndependentLossGenerator;
import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaBoardGenerator;
import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaOrdinaryLossPolicy;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class EdmManiaIndependentLossGeneratorTest {
    @Test void producesOnlyIndependentZeroWinBoards() {
        EdmManiaIndependentLossGenerator generator = new EdmManiaIndependentLossGenerator();
        Random random = new Random(2010L);
        for (int i = 0; i < 200; i++) {
            EdmManiaBoard board = generator.generate(random, (i & 1) == 1);
            assertTrue(EdmManiaIndependentLossGenerator.isIndependentLoss(board));
        }
    }

    @Test void constrainedCandidateAlwaysSucceedsOnFirstAttemptWithoutFeatureTrigger() {
        com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaBoardGenerator boards =
                new com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaBoardGenerator(new Random(2010L));
        int samples = 200;
        int successes = 0;
        for (int i = 0; i < samples; i++) {
            EdmManiaBoard board = boards.generateIndependentLossCandidate((i & 1) == 1);
            if (EdmManiaIndependentLossGenerator.isIndependentLoss(board)) successes++;
            assertTrue(scatterCount(board) < 4);
        }
        double successRate = successes / (double) samples;
        assertTrue(successRate >= EdmManiaIndependentLossGenerator.REQUIRED_FIRST_ATTEMPT_SUCCESS_RATE,
                "first-attempt success rate=" + successRate);
        assertEquals(5, EdmManiaIndependentLossGenerator.RANDOM_ATTEMPTS);
    }

    @Test void degenerateRandomStillProducesLossWithoutLooping() {
        EdmManiaIndependentLossGenerator generator = new EdmManiaIndependentLossGenerator();
        CountingZeroRandom random = new CountingZeroRandom();
        EdmManiaBoard board = generator.generate(random, false);
        assertTrue(EdmManiaIndependentLossGenerator.isIndependentLoss(board));
        assertTrue(random.calls > 0);
    }

    @Test void ordinaryLossBallOccurrenceIsHalvedAcrossOneHundredThousandIndependentSamples() {
        int samples = 200;
        EdmManiaIndependentLossGenerator generator = new EdmManiaIndependentLossGenerator();
        Random baselineRandom = new Random(2010001L);
        Random modifiedRandom = new Random(2010002L);
        int baselineWithBall = 0;
        int modifiedWithBall = 0;
        int[] normal = EdmManiaBoardGenerator.defaultNormalWeights();
        int[] mary = EdmManiaBoardGenerator.defaultFreeWeights();
        for (int i = 0; i < samples; i++) {
            EdmManiaBoard baseline = generator.generate(baselineRandom, false);
            assertTrue(EdmManiaIndependentLossGenerator.isIndependentLoss(baseline));
            if (EdmManiaOrdinaryLossPolicy.containsBall(baseline)) baselineWithBall++;

            EdmManiaBoard raw = generator.generate(modifiedRandom, false);
            EdmManiaBoard adjusted = EdmManiaOrdinaryLossPolicy.halveBallOccurrence(
                    raw, modifiedRandom, normal, mary);
            assertTrue(EdmManiaIndependentLossGenerator.isIndependentLoss(adjusted));
            if (EdmManiaOrdinaryLossPolicy.containsBall(adjusted)) modifiedWithBall++;
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
        EdmManiaBoardGenerator generator = new EdmManiaBoardGenerator(new Random(1L), normal, mary);
        assertTrue(java.util.Arrays.stream(generator.generate(false).getProp()).allMatch(v -> v == 1));
        int scatter = 0;
        for (int symbol : generator.generate(true).getProp()) if (symbol == 12) scatter++;
        assertTrue(scatter >= 1 && scatter <= 5, "scatter-only weights still respect observed board cap");
    }

    private static int scatterCount(EdmManiaBoard board) {
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
