package com.cpgame.sambasensation;

import com.cpgame.sambasensation.core.GameRuleCore;
import com.cpgame.sambasensation.core.MinimalRoundFactCodec;
import com.cpgame.sambasensation.core.ResultUtil;
import com.cpgame.sambasensation.generator.CompleteRoundFactory;
import com.cpgame.sambasensation.generator.GeneratorConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class RuleEngineTest {
    private static final Path CONFIG = Path.of("dist", "generator.properties");

    @Test void independentResultUtilMatchesProviderOracle() {
        int[] board = {1,0,1,0,7,0,0,1,10,1,0,5,1,10,0};
        GameRuleCore.CompleteRoundFact fact = new GameRuleCore.CompleteRoundFact(
                GameRuleCore.EntryKind.PAID_INITIAL, 1, 2, new int[]{0,1,0,0,0},
                GameRuleCore.CoinTransition.INCREMENT_NONDECREASING, 0,
                List.of(new GameRuleCore.Step(List.of(board))));
        ResultUtil.Evaluation result = ResultUtil.evaluate(fact);
        assertEquals(5900, result.multiplier());
        assertEquals(GameRuleCore.RoundClass.ORDINARY_WIN, result.roundClass());
        assertEquals(16, result.wins().size());
    }

    @Test void lossIsNaturallyZeroAndCodecRoundTrips() throws Exception {
        int[] board = {4,9,7,7,7,4,3,7,9,6,5,6,7,8,8};
        GameRuleCore.CompleteRoundFact fact = new GameRuleCore.CompleteRoundFact(
                GameRuleCore.EntryKind.PAID_INITIAL, 1, 0, new int[5],
                GameRuleCore.CoinTransition.UNCHANGED, 0, List.of(new GameRuleCore.Step(List.of(board))));
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec();
        String member = codec.encode(fact);
        assertFalse(member.startsWith("{"));
        assertFalse(member.startsWith("["));
        assertEquals(0, codec.verify(member).multiplier());
        assertEquals(member, codec.encode(codec.decode(member)));
    }

    @Test void generatedRoundsSurviveIndependentRoundTrip() throws Exception {
        GeneratorConfig config = GeneratorConfig.load(CONFIG);
        CompleteRoundFactory factory = new CompleteRoundFactory(config);
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec();
        SplittableRandom random = new SplittableRandom(2290L);
        boolean loss = false, win = false, free = false, coin = false;
        for (int i = 0; i < 2000; i++) {
            CompleteRoundFactory.GeneratedRound generated = factory.generateNatural(random);
            String member = codec.encode(generated.fact());
            ResultUtil.Evaluation verified = codec.verify(member);
            assertEquals(generated.evaluation().multiplier(), verified.multiplier());
            assertEquals(generated.evaluation().roundClass(), verified.roundClass());
            switch (verified.roundClass()) {
                case ORDINARY_LOSS -> loss = true;
                case ORDINARY_WIN -> win = true;
                case FREE_SPINS_SPECIAL -> free = true;
                case COIN_COLLECTION_REWARD -> coin = true;
            }
        }
        assertTrue(loss && win && free && coin);
    }

    @Test void explicitFreePartitionIsComplete() throws Exception {
        GeneratorConfig config = GeneratorConfig.load(CONFIG);
        GameRuleCore.CompleteRoundFact fact = new CompleteRoundFactory(config).generateFree(new SplittableRandom(5)).fact();
        assertEquals(GameRuleCore.FreePhase.ACTIVE, GameRuleCore.freePhase(fact, 0));
        assertEquals(GameRuleCore.FreePhase.ACTIVE, GameRuleCore.freePhase(fact, 4));
        assertEquals(GameRuleCore.FreePhase.TERMINAL, GameRuleCore.freePhase(fact, 5));
        assertEquals(0, GameRuleCore.freeRemaining(fact, 5));
    }

    @Test void crossRoundCollectionStateUsesOnlyConfirmedAdjacentBranches() {
        int[] board = {4,9,7,7,7,4,3,7,9,6,5,6,7,8,8};
        List<GameRuleCore.Step> step = List.of(new GameRuleCore.Step(List.of(board)));
        GameRuleCore.CollectionState state = GameRuleCore.CollectionState.initial();
        for (int slot = 0; slot < 4; slot++) {
            int[] delta = new int[5]; delta[slot] = 1;
            GameRuleCore.CompleteRoundFact increment = new GameRuleCore.CompleteRoundFact(
                    GameRuleCore.EntryKind.PAID_INITIAL, 1, 0, delta,
                    GameRuleCore.CoinTransition.INCREMENT_NONDECREASING, 0, step);
            state = GameRuleCore.applyCollectionTransition(state, increment).nextState();
        }
        GameRuleCore.CompleteRoundFact prematureOrdinary = new GameRuleCore.CompleteRoundFact(
                GameRuleCore.EntryKind.PAID_INITIAL, 1, 0, new int[]{0,0,0,0,1},
                GameRuleCore.CoinTransition.INCREMENT_NONDECREASING, 0, step);
        assertFalse(GameRuleCore.canApplyCollectionTransition(state, prematureOrdinary));
        int[] thirdBoard = board.clone(); thirdBoard[2] = 1;
        GameRuleCore.CompleteRoundFact full = new GameRuleCore.CompleteRoundFact(
                GameRuleCore.EntryKind.PAID_INITIAL, 3, 0, new int[5],
                GameRuleCore.CoinTransition.FULL_TRIGGER, 5,
                List.of(new GameRuleCore.Step(List.of(board, board, thirdBoard))));
        GameRuleCore.CollectionProjection reward = GameRuleCore.applyCollectionTransition(state, full);
        assertTrue(reward.fullReward());
        assertEquals(5, GameRuleCore.coinCount(reward.coins()));
        GameRuleCore.CompleteRoundFact unchanged = new GameRuleCore.CompleteRoundFact(
                GameRuleCore.EntryKind.PAID_INITIAL, 1, 0, new int[5],
                GameRuleCore.CoinTransition.UNCHANGED, 0, step);
        GameRuleCore.CollectionProjection reset = GameRuleCore.applyCollectionTransition(reward.nextState(), unchanged);
        assertEquals(GameRuleCore.CollectionBranch.RESET_AFTER_FULL, reset.branch());
        assertArrayEquals(new int[5], reset.coins());
    }

    @Test void tenThousandGeneratedRoundsFormLegalCrossRoundSequences() throws Exception {
        GeneratorConfig config = GeneratorConfig.load(CONFIG);
        CompleteRoundFactory factory = new CompleteRoundFactory(config);
        SplittableRandom random = new SplittableRandom(22900002L);
        int applied = 0;
        for (int session = 0; session < 100; session++) {
            GameRuleCore.CollectionState state = GameRuleCore.CollectionState.initial();
            for (int round = 0; round < 100; round++) {
                CompleteRoundFactory.GeneratedRound generated = null;
                for (int attempt = 0; attempt < 10000; attempt++) {
                    CompleteRoundFactory.GeneratedRound candidate = factory.generateNatural(random);
                    if (GameRuleCore.canApplyCollectionTransition(state, candidate.fact())) { generated = candidate; break; }
                }
                assertNotNull(generated, "applicable generated member must exist without local fallback");
                int expectedScatter = (state.scatterProgress() + generated.fact().scatterDelta()) % GameRuleCore.SCATTER_METER_SIZE;
                GameRuleCore.CollectionProjection projection = GameRuleCore.applyCollectionTransition(state, generated.fact());
                assertEquals(expectedScatter, projection.scatterProgress());
                state = projection.nextState();
                applied++;
            }
        }
        assertEquals(10000, applied);
    }
}
