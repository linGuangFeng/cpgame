package com.cpgame.crazypiggy.server;

import com.cpgame.crazypiggy.generator.GameRuleCore;
import com.cpgame.crazypiggy.generator.model.RoundResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class SessionContractTest {
    private static final class TestPreGeneratedStore extends RedisRoundStore {
        private final GameRuleCore fixtureBuilder = GameRuleCore.forTesting(56099);
        int claims;
        @Override public RoundResult claim(BigDecimal bs, int bl) {
            claims++;
            return fixtureBuilder.generateIndependentLoss(bs, bl);
        }
    }

    @Test void launchAndRuntimeTokensHaveDifferentRoles() {
        SessionService service = new SessionService(new BigDecimal("100.00"), new TestPreGeneratedStore());
        PlayerSession session = service.verify("launch-token");
        assertNotEquals(session.launchToken(), session.runtimeToken());
        assertNull(service.authenticate("launch-token"));
        assertSame(session, service.authenticate(session.runtimeToken()));
    }

    @Test void idempotentRetryClaimsAndChargesOnlyOnce() {
        TestPreGeneratedStore store = new TestPreGeneratedStore();
        PlayerSession session = new SessionService(new BigDecimal("100.00"), store).verify("launch");
        var first = session.spin(new BigDecimal("0.5"), 1, "same-request");
        BigDecimal afterFirst = session.balance();
        var replay = session.spin(new BigDecimal("0.5"), 1, "same-request");
        assertEquals(first, replay);
        assertEquals(0, afterFirst.compareTo(session.balance()));
        assertEquals(1, store.claims);
        assertEquals(1, session.historyCount());
    }

    @Test void configRestoreAndHistoryProjectClaimedRound() {
        PlayerSession session = new SessionService(new BigDecimal("100.00"), new TestPreGeneratedStore()).verify("launch");
        session.spin(new BigDecimal("0.5"), 1, null);
        assertNotNull(session.last());
        assertEquals(1, session.historyCount());
        assertEquals(1, session.historyList(1).get("lc"));
    }
}
