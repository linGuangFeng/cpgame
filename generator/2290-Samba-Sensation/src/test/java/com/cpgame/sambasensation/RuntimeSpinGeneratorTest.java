package com.cpgame.sambasensation;

import com.cpgame.sambasensation.core.GameRuleCore;
import com.cpgame.sambasensation.generator.RuntimeSpinGenerator;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeSpinGeneratorTest {
    @Test void twoMissingCoinSlotsCanBeFilledByTheLiveDelta() {
        RuntimeSpinGenerator generator = new RuntimeSpinGenerator(parameters(
                new int[]{0, 0, 0, 1, 1}, new int[]{1, 0, 0, 0}));
        GameRuleCore.CollectionState state = new GameRuleCore.CollectionState(
                0, new int[]{2, 3, 4, 0, 0}, false);

        RuntimeSpinGenerator.CoinDecision decision = generator.coinDecision(
                new ZeroRandom(), state, true, 20);

        assertEquals(GameRuleCore.CoinTransition.FULL_TRIGGER, decision.transition());
        assertArrayEquals(new int[]{0, 0, 0, 1, 1}, decision.delta());
        assertEquals(11, decision.rewardCount());
        assertTrue(GameRuleCore.applyCollectionTransition(state, fact(decision)).fullReward());
    }

    @Test void zeroMultiplierPaidPageIsGeneratedLiveWithinThePassedScatterLimit() {
        RuntimeSpinGenerator generator = new RuntimeSpinGenerator(parameters(
                new int[]{1, 1, 1, 1, 1}, new int[]{1, 0, 0, 0}));

        RuntimeSpinGenerator.PaidPage page = generator.paidPage(
                new CyclingRandom(), 1, 0, 0, 0, true);

        assertEquals(0, page.scatterDelta());
        assertEquals(0, page.multiplier());
        assertFalse(page.boards().isEmpty());
    }

    private static RuntimeSpinGenerator.Parameters parameters(int[] positions, int[] increments) {
        int[] symbols = {0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0};
        return new RuntimeSpinGenerator.Parameters(
                new int[][][]{{symbols}, {symbols, symbols}, {symbols, symbols, symbols}},
                0, 1, 0, 1, positions, increments, 100);
    }

    private static GameRuleCore.CompleteRoundFact fact(RuntimeSpinGenerator.CoinDecision decision) {
        int[] board = {1,2,3,4,5,6,7,8,9,1,2,3,4,5,6};
        return new GameRuleCore.CompleteRoundFact(GameRuleCore.EntryKind.PAID_INITIAL, 3, 0,
                decision.delta(), decision.transition(), decision.rewardCount(),
                java.util.List.of(new GameRuleCore.Step(java.util.List.of(board, board, board))));
    }

    private static class ZeroRandom extends SecureRandom {
        @Override public long nextLong(long bound) { return 0; }
    }

    private static final class CyclingRandom extends SecureRandom {
        private long next;
        @Override public long nextLong(long bound) { return next++ % bound; }
    }
}
