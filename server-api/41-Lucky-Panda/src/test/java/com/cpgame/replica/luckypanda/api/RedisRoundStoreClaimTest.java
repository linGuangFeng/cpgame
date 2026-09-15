package com.cpgame.replica.luckypanda.api;

import com.cpgame.replica.luckypanda.CompleteRoundCodec;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.RoundClass;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisRoundStoreClaimTest {
    @Test
    void emptyCacheFailsWithoutDealing() {
        RedisRoundStore store = new RedisRoundStore(new FakeRedisCommands(), 41L);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> store.claim(new SecureRandom()));
        assertTrue(ex.getMessage().contains("empty"));
        assertFalse(ex.getMessage().toLowerCase().contains("memory"));
        assertFalse(ex.getMessage().toLowerCase().contains("fixture"));
    }

    @Test
    void lpopClaimsLossMemberOnce() throws Exception {
        FakeRedisCommands fake = new FakeRedisCommands();
        String loss = TestMembers.lossMember();
        fake.seed(false, 0, loss);
        RedisRoundStore store = new RedisRoundStore(fake, 41L);
        RedisRoundStore.ClaimedRound claimed = store.claim(new AlwaysLossRandom());
        assertEquals(RoundClass.ORDINARY_LOSS, claimed.kind());
        assertEquals(0, claimed.ratio());
        assertFalse(claimed.member().startsWith("{"));
        assertEquals(loss, claimed.member());
        assertTrue(claimed.member().matches("#[0-9]+"));
        IllegalStateException empty = assertThrows(IllegalStateException.class, () -> store.claim(new AlwaysLossRandom()));
        assertTrue(empty.getMessage().contains("empty"));
    }

    @Test
    void jsonMemberIsRejected() {
        FakeRedisCommands fake = new FakeRedisCommands();
        fake.seed(false, 0, "{\"rskl\":[]}");
        RedisRoundStore store = new RedisRoundStore(fake, 41L);
        assertThrows(IllegalStateException.class, () -> store.claim(new AlwaysLossRandom()));
    }

    @Test
    void winBucketIsOrdinaryWinNotMaryUnlessScatterFree() throws Exception {
        FakeRedisCommands fake = new FakeRedisCommands();
        CompleteRoundCodec codec = new CompleteRoundCodec();
        var win = TestMembers.generate(RoundClass.ORDINARY_WIN, new Random(41), 4000);
        fake.seed(false, win.actualMultiplier(), codec.encode(win.fact()));
        RedisRoundStore store = new RedisRoundStore(fake, 41L);
        RedisRoundStore.ClaimedRound claimed = store.claim(new AlwaysWinRandom());
        assertEquals(RoundClass.ORDINARY_WIN, claimed.kind());
        assertTrue(claimed.ratio() > 0);
        assertFalse(claimed.special());
    }

    private static final class AlwaysLossRandom extends SecureRandom {
        @Override public boolean nextBoolean() { return false; }
        @Override public int nextInt(int bound) { return 0; }
    }

    private static final class AlwaysWinRandom extends SecureRandom {
        @Override public boolean nextBoolean() { return true; }
        @Override public int nextInt(int bound) { return 0; }
    }
}
