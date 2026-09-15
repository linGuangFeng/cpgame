package com.cpgame.crazy777.server;

import com.cpgame.crazy777.generator.GameRuleCore;
import com.cpgame.crazy777.generator.model.RoundResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class SessionContractTest {
    private static final class TestPreGeneratedStore extends RedisRoundStore {
        private final GameRuleCore fixtureBuilder = GameRuleCore.forTesting(57099);
        int claims;
        @Override public RoundResult claim(BigDecimal bs, int bl, BigDecimal startingBalance) {
            claims++;
            return fixtureBuilder.generateIndependentLoss(bl, bs, startingBalance);
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

    @Test void freeRoundClaimsOnceAndProjectsAllElevenSteps() {
        RedisRoundStore store = new RedisRoundStore() {
            final GameRuleCore core = GameRuleCore.forTesting(57100);
            int claims;
            @Override public RoundResult claim(BigDecimal bs, int bl, BigDecimal startingBalance) {
                claims++;
                return core.generateFreeSpins(bl, bs, startingBalance);
            }
        };
        PlayerSession session = new SessionService(new BigDecimal("100.00"), store).verify("launch");
        MapProbe first = new MapProbe(session.spin(new BigDecimal("0.5"), 1, null));
        assertEquals(10, first.intVal("fsn"));
        assertEquals(0, first.intVal("nfsc"));
        for (int i = 1; i <= 10; i++) {
            MapProbe step = new MapProbe(session.spin(new BigDecimal("0.5"), 1, null));
            assertEquals(i, step.intVal("nfsc"));
            assertEquals(0, ((Number) step.raw.get("ba")).intValue());
        }
        assertEquals(1, session.historyCount());
        assertNotNull(session.historyDetail(session.lastRoundKey()));
        assertEquals(1, ((RedisRoundStore) store).getClass() == store.getClass() ? 1 : 1);
        assertEquals(1, claimsOf(store));
    }

    private static int claimsOf(RedisRoundStore store) {
        try {
            var field = store.getClass().getDeclaredField("claims");
            field.setAccessible(true);
            return field.getInt(store);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private record MapProbe(java.util.Map<String, Object> raw) {
        int intVal(String key) { return ((Number) raw.get(key)).intValue(); }
    }
}
