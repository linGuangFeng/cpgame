package com.cpgame.replica.hotpot;

import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotIndependentLossGenerator;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotRoundKind;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RedisRoundStoreClaimTest {
    @Test
    void emptyCacheFailsWithoutDealing() {
        RedisRoundStore store = new RedisRoundStore(new FakeRedisCommands(), 1830L);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> store.claim(new SecureRandom()));
        assertTrue(ex.getMessage().contains("empty"));
        assertTrue(ex.getMessage().contains("gameId=1830"));
        assertTrue(ex.getMessage().contains("db=0"));
        assertFalse(ex.getMessage().contains("db=15"));
        assertFalse(ex.getMessage().toLowerCase().contains("memory"));
    }

    @Test
    void selectsWinOrLossThenExistingMultiplier() throws Exception {
        FakeRedisCommands fake = new FakeRedisCommands();
        CompleteRoundCodec codec = new CompleteRoundCodec();
        String loss = codec.encode(lossFact());
        fake.seed(false, 0, loss);
        RedisRoundStore store = new RedisRoundStore(fake, 1830L);
        RedisRoundStore.ClaimedRound claimed = store.claim(new AlwaysLossRandom());
        assertEquals(HotpotRoundKind.ORDINARY_LOSS, claimed.kind());
        assertEquals(0, claimed.ratio());
        assertFalse(claimed.member().startsWith("{"));
        assertEquals("#", claimed.member());
        assertEquals(claimed.verification(), codec.verify(claimed.fact(), 10, 30));
    }

    @Test
    void claimKindReadsRequestedPool() throws Exception {
        FakeRedisCommands fake = new FakeRedisCommands();
        CompleteRoundCodec codec = new CompleteRoundCodec();
        fake.seed(false, 0, codec.encode(lossFact()));
        CompleteRoundFactory factory = new CompleteRoundFactory();
        Random random = new Random(1830L);
        CompleteRoundFactory.GeneratedRound win = null;
        CompleteRoundFactory.GeneratedRound scatter = null;
        for (int i = 0; i < 8000 && (win == null || scatter == null); i++) {
            try {
                CompleteRoundFactory.GeneratedRound generated = factory.generate(random, 10, 30);
                if (generated.kind() == HotpotRoundKind.ORDINARY_WIN && win == null) win = generated;
                if (generated.kind() == HotpotRoundKind.SCATTER_FREE_SPINS && scatter == null) scatter = generated;
            } catch (CompleteRoundFactory.RoundRejectedException ignored) {
            }
        }
        assertNotNull(win);
        assertNotNull(scatter);
        fake.seed(false, win.multiplier(), codec.encode(win.fact()));
        fake.seed(true, scatter.multiplier(), codec.encode(scatter.fact()));
        RedisRoundStore store = new RedisRoundStore(fake, 1830L);
        AlwaysLossRandom pick = new AlwaysLossRandom();
        assertEquals(HotpotRoundKind.ORDINARY_LOSS, store.claimKind(HotpotRoundKind.ORDINARY_LOSS, pick).kind());
        assertEquals(HotpotRoundKind.ORDINARY_WIN, store.claimKind(HotpotRoundKind.ORDINARY_WIN, pick).kind());
        assertEquals(HotpotRoundKind.SCATTER_FREE_SPINS, store.claimKind(HotpotRoundKind.SCATTER_FREE_SPINS, pick).kind());
    }

    @Test
    void emptyLossPoolFallsBackToExistingWin() throws Exception {
        FakeRedisCommands fake = new FakeRedisCommands();
        CompleteRoundCodec codec = new CompleteRoundCodec();
        CompleteRoundFactory factory = new CompleteRoundFactory();
        Random random = new Random(1830L);
        CompleteRoundFactory.GeneratedRound win = null;
        for (int i = 0; i < 8000 && win == null; i++) {
            try {
                CompleteRoundFactory.GeneratedRound generated = factory.generate(random, 10, 30);
                if (generated.kind() == HotpotRoundKind.ORDINARY_WIN) win = generated;
            } catch (CompleteRoundFactory.RoundRejectedException ignored) {
            }
        }
        assertNotNull(win);
        fake.seed(false, win.multiplier(), codec.encode(win.fact()));
        RedisRoundStore store = new RedisRoundStore(fake, 1830L);
        RedisRoundStore.ClaimedRound claimed = store.claim(new AlwaysLossRandom());
        assertEquals(HotpotRoundKind.ORDINARY_WIN, claimed.kind());
        assertEquals(win.multiplier(), claimed.ratio());
    }

    @Test
    void jsonMemberIsRejected() {
        FakeRedisCommands fake = new FakeRedisCommands();
        fake.seed(false, 0, "{\"prop\":[]}");
        RedisRoundStore store = new RedisRoundStore(fake, 1830L);
        assertThrows(IllegalStateException.class, () -> store.claim(new AlwaysLossRandom()));
    }

    private static CompleteRoundFact lossFact() {
        HotpotIndependentLossGenerator losses = new HotpotIndependentLossGenerator();
        return new CompleteRoundFact(CompleteRoundFact.VERSION,
                List.of(List.of(CompleteRoundFact.fromBoard(losses.generate(new Random(1830L))))));
    }

    private static final class AlwaysLossRandom extends SecureRandom {
        @Override public boolean nextBoolean() { return false; }
        @Override public int nextInt(int bound) { return 0; }
        @Override public long nextLong(long origin, long bound) { return bound - 1; }
        @Override public long nextLong(long bound) { return 0; }
    }
}
