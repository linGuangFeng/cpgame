package com.cpgame.sambasensation.server;

import com.cpgame.sambasensation.core.GameRuleCore;
import com.cpgame.sambasensation.generator.RuntimeSpinGenerator;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeRoundComposerTest {
    @Test void allFloorsUseCachedAwardsEvenWhenLiveWeightsCannotGenerateAWin() throws Exception {
        for (int floor = 1; floor <= 3; floor++) {
            FakeRedisCommands redis = new FakeRedisCommands();
            String member = TestRoundMembers.win(floor);
            int expected = TestRoundMembers.multiplier(member);
            redis.seedFloor(floor, expected, member);
            RuntimeRoundComposer composer = new RuntimeRoundComposer(new RedisRoundStore(redis, 2290),
                    scatterOnlyGenerator(), new int[]{9000}, new int[]{1}, 0, 1, 1, 0);
            GameRuleCore.CollectionState state = new GameRuleCore.CollectionState(28, new int[5], false);
            RedisRoundStore.ClaimedRound round = composer.composePaid(new ConstraintRandom(), floor, state);
            assertEquals(expected, round.multiplier());
            assertEquals(floor, round.fact().steps().get(0).boards().size());
            assertTrue(round.fact().scatterDelta() <= 1);
            assertEquals("cached-ordinary-win", round.member());
            assertEquals(1, round.fact().steps().size());
            int requestedFloor = floor;
            assertThrows(IllegalStateException.class, () -> composer.composePaid(new ConstraintRandom(), requestedFloor, state));
        }
    }

    @Test void cacheDoesNotBorrowAWinningBoardFromAnotherFloor() throws Exception {
        FakeRedisCommands redis = new FakeRedisCommands();
        String member = TestRoundMembers.win(1);
        redis.seedFloor(1, TestRoundMembers.multiplier(member), member);
        RuntimeRoundComposer composer = new RuntimeRoundComposer(new RedisRoundStore(redis, 2290),
                scatterOnlyGenerator(), new int[]{9000}, new int[]{1}, 0, 1, 1, 0);
        assertThrows(IllegalStateException.class, () -> composer.composePaid(
                new ConstraintRandom(), 2, GameRuleCore.CollectionState.initial()));
    }

    private static RuntimeSpinGenerator scatterOnlyGenerator() {
        int[] symbols = {0,0,0,0,0,0,0,0,0,0,1};
        return new RuntimeSpinGenerator(new RuntimeSpinGenerator.Parameters(
                new int[][][]{{symbols}, {symbols,symbols}, {symbols,symbols,symbols}},
                1,0,1,0,new int[]{1,1,1,1,1},new int[]{1,0,0,0},1));
    }

    @Test void coin99AddsOnlyACachedAwardWithinTheRemainingOneOdds() throws Exception {
        FakeRedisCommands redis = new FakeRedisCommands();
        java.util.List<int[]> boards = TestRoundMembers.paidFact(3, true).steps().get(0).boards();
        for (int[] board : boards) for (int i = 0; i < board.length; i++) board[i] = (board[i] - 1 + 6) % 9 + 1;
        GameRuleCore.CompleteRoundFact cached = new GameRuleCore.CompleteRoundFact(
                GameRuleCore.EntryKind.PAID_INITIAL, 3, 0, new int[5],
                GameRuleCore.CoinTransition.UNCHANGED, 0,
                java.util.List.of(new GameRuleCore.Step(boards)));
        String member = new com.cpgame.sambasensation.core.MinimalRoundFactCodec().encode(cached);
        int extra = TestRoundMembers.multiplier(member);
        assertTrue(extra > 0 && extra <= 25);
        redis.seedFloor(3, extra, member);
        int[] symbols = {0,0,0,0,0,0,0,0,0,0,1};
        RuntimeSpinGenerator generator = new RuntimeSpinGenerator(new RuntimeSpinGenerator.Parameters(
                new int[][][]{{symbols},{symbols,symbols},{symbols,symbols,symbols}},
                0,1,1,0,new int[]{0,0,0,0,1},new int[]{1,0,0,0},1));
        RuntimeRoundComposer composer = new RuntimeRoundComposer(new RedisRoundStore(redis,2290),
                generator,new int[]{100},new int[]{1},0,1,1,0);
        GameRuleCore.CollectionState state = new GameRuleCore.CollectionState(29,new int[]{24,24,24,26,0},false);
        RedisRoundStore.ClaimedRound result = composer.composePaid(new ConstraintRandom(),3,state);
        assertEquals(99 * 25 + extra,result.multiplier());
        assertTrue(result.multiplier() <= 100 * 25);
        assertEquals(99,result.fact().coinRewardCount());
        assertEquals(0,result.fact().scatterDelta());
    }

    @Test void coinFullAtScatter29UsesALiveZeroPageWhenRewardConsumesTheCap() throws Exception {
        RuntimeRoundComposer composer = composer(new FakeRedisCommands(), 11, 1, 0,
                new int[]{0, 0, 0, 1, 1});
        GameRuleCore.CollectionState state = new GameRuleCore.CollectionState(
                29, new int[]{2, 3, 4, 0, 0}, false);

        RedisRoundStore.ClaimedRound round = composer.composePaid(new ConstraintRandom(), 3, state);

        assertEquals(GameRuleCore.RoundClass.COIN_COLLECTION_REWARD, round.kind());
        assertEquals(11 * 25, round.multiplier());
        assertEquals(0, round.fact().scatterDelta());
        assertArrayEquals(new int[]{0, 0, 0, 1, 1}, round.fact().coinDelta());
        assertEquals("runtime-coin-full", round.member());
        assertTrue(GameRuleCore.applyCollectionTransition(state, round.fact()).fullReward());
    }

    @Test void naturalMaryJoinsALivePaidPageToACachedFiveStepTail() throws Exception {
        FakeRedisCommands redis = new FakeRedisCommands();
        String cached = TestRoundMembers.naturalFree();
        int cachedMultiplier = TestRoundMembers.multiplier(cached);
        redis.seed(true, cachedMultiplier, cached);
        RuntimeRoundComposer composer = composer(redis, 10_000, 0, 1,
                new int[]{1, 1, 1, 1, 1});

        RedisRoundStore.ClaimedRound round = composer.composePaid(
                new ConstraintRandom(), 1, GameRuleCore.CollectionState.initial());

        assertEquals(GameRuleCore.RoundClass.FREE_SPINS_SPECIAL, round.kind());
        assertEquals(6, round.fact().steps().size());
        assertEquals(0, round.fact().scatterDelta());
        assertEquals("runtime-natural-mary", round.member());
        assertEquals(0, redis.size(true, cachedMultiplier));
        assertTrue(round.multiplier() <= 10_000 * 25);
    }

    @Test void electedMaryCanFillScatter28AndStillUseTheCachedTail() throws Exception {
        FakeRedisCommands redis = new FakeRedisCommands();
        String member = TestRoundMembers.naturalFree();
        redis.seed(true,TestRoundMembers.multiplier(member),member);
        int[] symbols = {0,1,1,1,1,1,1,1,1,1,1};
        RuntimeSpinGenerator generator = new RuntimeSpinGenerator(new RuntimeSpinGenerator.Parameters(
                new int[][][]{{symbols},{symbols,symbols},{symbols,symbols,symbols}},
                1,0,1,0,new int[]{1,1,1,1,1},new int[]{1,0,0,0},1));
        RuntimeRoundComposer composer = new RuntimeRoundComposer(new RedisRoundStore(redis,2290),
                generator,new int[]{10000},new int[]{1},1,0,1,0);
        SecureRandom random = new SecureRandom() {
            final int[] board={10,10,3,4,5,6,7,8,9,1,2,3,4,5,6};
            int cursor;
            @Override public long nextLong(long bound) { return bound==10?board[cursor++ % 15]-1:0; }
            @Override public int nextInt(int bound) { return 0; }
        };
        GameRuleCore.CollectionState state = new GameRuleCore.CollectionState(28,new int[5],false);
        RedisRoundStore.ClaimedRound round = composer.composePaid(random,1,state);
        assertEquals("runtime-scatter-full",round.member());
        assertEquals(2,round.fact().scatterDelta());
        assertEquals(0,GameRuleCore.applyCollectionTransition(state,round.fact()).scatterProgress());
        assertEquals(TestRoundMembers.multiplier(member),round.multiplier());
    }

    private static RuntimeRoundComposer composer(FakeRedisCommands redis, int capOdds,
                                                   int ordinaryWeight, int maryWeight,
                                                   int[] coinPositions) {
        int[] symbols = {0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0};
        RuntimeSpinGenerator.Parameters parameters = new RuntimeSpinGenerator.Parameters(
                new int[][][]{{symbols}, {symbols, symbols}, {symbols, symbols, symbols}},
                ordinaryWeight == 1 ? 0 : 1, ordinaryWeight == 1 ? 1 : 0,
                0, 1, coinPositions, new int[]{1, 0, 0, 0}, 100);
        return new RuntimeRoundComposer(new RedisRoundStore(redis, 2290),
                new RuntimeSpinGenerator(parameters), new int[]{capOdds}, new int[]{1},
                maryWeight, ordinaryWeight);
    }

    private static final class ConstraintRandom extends SecureRandom {
        private long symbol;
        @Override public long nextLong(long bound) {
            return bound == 9 ? symbol++ % bound : 0;
        }
        @Override public int nextInt(int bound) { return 0; }
    }
}
