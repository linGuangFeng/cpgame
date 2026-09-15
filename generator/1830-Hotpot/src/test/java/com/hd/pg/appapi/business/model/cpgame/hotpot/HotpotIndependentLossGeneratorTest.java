package com.hd.pg.appapi.business.model.cpgame.hotpot;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class HotpotIndependentLossGeneratorTest {
    @Test void producesOnlyIndependentZeroWinBoards() {
        HotpotIndependentLossGenerator generator = new HotpotIndependentLossGenerator();
        Random random = new Random(1830L);
        for (int i = 0; i < 1_000; i++) {
            HotpotBoard board = generator.generate(random);
            assertTrue(HotpotIndependentLossGenerator.isIndependentLoss(board));
        }
    }

    @Test void constrainedCandidateAlwaysSucceedsOnFirstAttemptWithoutFeatureTrigger() {
        HotpotBoardGenerator boards = new HotpotBoardGenerator(new Random(1830L));
        int samples = 100_000;
        int successes = 0;
        for (int i = 0; i < samples; i++) {
            HotpotBoard board = boards.generateIndependentLossCandidate();
            if (HotpotIndependentLossGenerator.isIndependentLoss(board)) successes++;
            HotpotEvaluation evaluation = HotpotResultUtil.evaluate(board);
            assertTrue(evaluation.getScatterCount() < 3);
        }
        double successRate = successes / (double) samples;
        assertTrue(successRate >= HotpotIndependentLossGenerator.REQUIRED_FIRST_ATTEMPT_SUCCESS_RATE,
                "first-attempt success rate=" + successRate);
        assertEquals(samples, successes);
        assertEquals(5, HotpotIndependentLossGenerator.RANDOM_ATTEMPTS);
    }

    @Test void roundKindPartitionHasNoBuyAndNoElseHole() {
        HotpotGameRuleCore core = new HotpotGameRuleCore();
        assertEquals(HotpotRoundKind.SCATTER_FREE_SPINS, core.classifyRound(true, 0));
        assertEquals(HotpotRoundKind.SCATTER_FREE_SPINS, core.classifyRound(true, 12));
        assertEquals(HotpotRoundKind.ORDINARY_LOSS, core.classifyRound(false, 0));
        assertEquals(HotpotRoundKind.ORDINARY_WIN, core.classifyRound(false, 8));
        assertEquals(3, HotpotRoundKind.values().length);
        assertEquals(1830, core.rawGameId());
        assertTrue(core.implementationAllowed());
    }
}
