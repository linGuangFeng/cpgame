package com.hd.cpgame.magicscroll2.server;

import com.hd.cpgame.magicscroll2.core.GameRuleCore;
import com.hd.cpgame.magicscroll2.core.GeneratedRound;
import com.hd.cpgame.magicscroll2.core.GenerationPolicy;
import com.hd.cpgame.magicscroll2.core.RoundMode;
import com.hd.cpgame.magicscroll2.core.SymbolWeightPolicy;
import com.hd.cpgame.magicscroll2.core.TrialProbabilityPolicy;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class SessionContractTest {
    private static final class TestStore extends RedisRoundStore {
        private final GameRuleCore core = new GameRuleCore(GenerationPolicy.defaults(),
                new TrialProbabilityPolicy(1, 1, 1, 1), SymbolWeightPolicy.localReplicaDefaults());
        int claims;
        RoundMode mode = RoundMode.LOSS;
        @Override public GeneratedRound claim(BigDecimal paidBet) {
            claims++;
            return core.generateCompleteRound(paidBet, mode);
        }
    }

    @Test
    public void launchAndRuntimeTokensHaveDifferentRoles() {
        SessionService service = new SessionService(new BigDecimal("100.00"), new TestStore());
        PlayerSession session = service.verify("2001007", "launch-token", "pt");
        assertNotEquals(session.otk(), session.atk());
        assertSame(session, service.require(session.atk()));
        try {
            new SessionService(new BigDecimal("100.00"), new TestStore()).require(session.atk());
            throw new AssertionError("unknown atk must fail");
        } catch (ApiException expected) {
            assertEquals("SESSION_INVALID", expected.getCode());
        }
    }

    @Test
    public void paidLossClaimsOnceAndContinuationsAreRejectedAfterTerminal() {
        TestStore store = new TestStore();
        PlayerSession session = new SessionService(new BigDecimal("100.00"), store).verify("2001007", "otk", "en");
        Map<String, Object> first = session.spin(new BigDecimal("0.40"), BigDecimal.ONE, 1, false, "same");
        assertEquals(false, first.get("free"));
        assertTrue(((String) first.get("formation")).contains(","));
        BigDecimal after = session.balance();
        Map<String, Object> replay = session.spin(new BigDecimal("0.40"), BigDecimal.ONE, 1, false, "same");
        assertEquals(first.get("formation"), replay.get("formation"));
        assertEquals(0, after.compareTo(session.balance()));
        assertEquals(1, store.claims);
        assertEquals(1, session.history().size());
    }

    @Test
    public void xsplitProjectsEveryDeliveryFromOneClaim() {
        TestStore store = new TestStore();
        store.mode = RoundMode.XSPLIT;
        PlayerSession session = new SessionService(new BigDecimal("100.00"), store).verify("2001007", "otk", "pt");
        Map<String, Object> first = session.spin(new BigDecimal("0.40"), BigDecimal.ONE, 1, false, null);
        int extra = 0;
        while (true) {
            try {
                session.spin(BigDecimal.ZERO, BigDecimal.ZERO, 1, false, null);
                extra++;
            } catch (ApiException end) {
                assertEquals("ROUND_TERMINAL", end.getCode());
                break;
            }
        }
        assertTrue(extra >= 1);
        assertEquals(1, store.claims);
        assertEquals(1, session.history().size());
        assertTrue(first.get("formation") != null);
    }

    @Test
    public void redisEmptyFailsWithoutLiveGeneration() {
        RedisRoundStore empty = new RedisRoundStore() {
            @Override public GeneratedRound claim(BigDecimal paidBet) {
                throw new PoolUnavailableException("REDIS_POOL_EMPTY", "BetLog:02001007:000000");
            }
        };
        PlayerSession session = new SessionService(new BigDecimal("100.00"), empty).verify("2001007", "otk", "pt");
        try {
            session.spin(new BigDecimal("0.40"), BigDecimal.ONE, 1, false, null);
            throw new AssertionError("empty redis must fail");
        } catch (RedisRoundStore.PoolUnavailableException expected) {
            assertEquals("REDIS_POOL_EMPTY", expected.code());
        }
        assertEquals(0, session.history().size());
    }
}
