package com.cpgame.sambasensation.server;

import com.cpgame.sambasensation.core.GameRuleCore;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

class RedisRoundStoreTest {
    @Test void emptyCacheFailsAndNeverDealsLocally() {
        RedisRoundStore store = new RedisRoundStore(new FakeRedisCommands(), 2290);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> store.claimPaid(
                new FixedRandom(false), 1, GameRuleCore.CollectionState.initial()));
        assertTrue(error.getMessage().contains("192.168.10.3:6379 db=15"));
    }

    @Test void paidClaimFiltersBetTypeAndAtomicallyRemovesOneMember() throws Exception {
        FakeRedisCommands redis = new FakeRedisCommands();
        String oneAxis = TestRoundMembers.loss(1), threeAxes = TestRoundMembers.loss(3);
        redis.seed(false, 0, oneAxis, threeAxes);
        RedisRoundStore.ClaimedRound claimed = new RedisRoundStore(redis, 2290).claimPaid(
                new FixedRandom(false), 3, GameRuleCore.CollectionState.initial());
        assertEquals(3, claimed.fact().betType());
        assertEquals(GameRuleCore.RoundClass.ORDINARY_LOSS, claimed.kind());
        assertEquals(1, redis.size(false, 0));
    }

    @Test void purchaseUsesMaryPoolButOnlyFeatureBuyEntry() throws Exception {
        FakeRedisCommands redis = new FakeRedisCommands();
        String natural = TestRoundMembers.naturalFree(), buy = TestRoundMembers.featureBuy();
        int multiplier = TestRoundMembers.multiplier(buy);
        assertEquals(multiplier, TestRoundMembers.multiplier(natural));
        redis.seed(true, multiplier, natural, buy);
        RedisRoundStore.ClaimedRound claimed = new RedisRoundStore(redis, 2290).claimFeatureBuy(
                new FixedRandom(true), GameRuleCore.CollectionState.initial());
        assertEquals(GameRuleCore.EntryKind.FEATURE_BUY_INITIAL, claimed.fact().entryKind());
        assertEquals(GameRuleCore.RoundClass.FREE_SPINS_SPECIAL, claimed.kind());
        assertEquals(1, redis.size(true, multiplier));
    }

    @Test void normalPaidClaimChoosesLossOrWinBeforeRandomMultiplier() throws Exception {
        FakeRedisCommands redis = new FakeRedisCommands();
        String loss = TestRoundMembers.loss(1);
        String win = TestRoundMembers.win(1);
        redis.seed(false, 0, loss);
        redis.seed(false, TestRoundMembers.multiplier(win), win);
        RedisRoundStore store = new RedisRoundStore(redis, 2290);
        assertEquals(GameRuleCore.RoundClass.ORDINARY_LOSS, store.claimPaid(
                new FixedRandom(false), 1, GameRuleCore.CollectionState.initial()).kind());
        assertEquals(GameRuleCore.RoundClass.ORDINARY_WIN, store.claimPaid(
                new FixedRandom(true), 1, GameRuleCore.CollectionState.initial()).kind());
    }

    static class FixedRandom extends SecureRandom {
        private final boolean value;
        FixedRandom(boolean value) { this.value = value; }
        @Override public boolean nextBoolean() { return value; }
        @Override public int nextInt(int bound) { return 0; }
    }
}
