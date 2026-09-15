package com.cpgame.saci.server;

import com.cpgame.saci.generator.GameRuleCore;
import com.cpgame.saci.generator.GameRules;
import com.cpgame.saci.generator.ResultUtil;
import com.cpgame.saci.generator.model.RoundMode;
import com.cpgame.saci.generator.model.RoundResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionContractTest {
    private static final class TestPreGeneratedStore extends RedisRoundStore {
        private final GameRuleCore fixtureBuilder = GameRuleCore.forTesting(61099);
        int claims;
        @Override public RoundResult claim(BigDecimal bs, int bl, BigDecimal startingBalance,
                                           com.cpgame.saci.generator.ResultUtil.EnergyState energy) {
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
        var first = session.spin(new BigDecimal("0.02"), 1, "same-request");
        BigDecimal afterFirst = session.balance();
        var replay = session.spin(new BigDecimal("0.02"), 1, "same-request");
        assertEquals(first, replay);
        assertEquals(0, afterFirst.compareTo(session.balance()));
        assertEquals(1, store.claims);
        assertEquals(1, session.historyCount());
    }

    @Test void specialRoundClaimsOnceAndProjectsAllSteps() {
        RedisRoundStore store = new RedisRoundStore() {
            final GameRuleCore core = GameRuleCore.forTesting(61100);
            int claims;
            @Override public RoundResult claim(BigDecimal bs, int bl, BigDecimal startingBalance,
                                               com.cpgame.saci.generator.ResultUtil.EnergyState energy) {
                claims++;
                return core.generateSpecial(bl, bs, startingBalance);
            }
        };
        PlayerSession session = new SessionService(new BigDecimal("10000.00"), store).verify("launch");
        Map<String, Object> first = session.spin(new BigDecimal("0.02"), 1, null);
        int steps = 1;
        while (((Number) session.last().get("ss")).intValue() != 1
                || ((Number) session.last().get("fsn")).intValue() != ((Number) session.last().get("nfsc")).intValue()
                || ((Number) session.last().get("rsn")).intValue() != ((Number) session.last().get("nrsc")).intValue()) {
            session.spin(new BigDecimal("0.02"), 1, null);
            steps++;
            if (steps > 80) break;
        }
        assertEquals(1, session.historyCount());
        assertEquals(1, ((Number) first.get("gt")).intValue() >= 1 ? 1 : 0);
    }

    @Test void energyPersistsAcrossOrdinaryRoundsAndTriggersVortexFromCache() {
        GameRuleCore core = GameRuleCore.forTesting(61161);
        BigDecimal bs = new BigDecimal("0.02");
        BigDecimal start = new BigDecimal("10000");
        RoundResult withWild = null;
        RoundResult vortex = null;
        for (int i = 0; i < 400; i++) {
            RoundResult win = core.generateOrdinaryWin(1, bs, start);
            if (ResultUtil.energyDelta(win.candidate().steps()) == 1) {
                withWild = win;
                break;
            }
        }
        for (int i = 0; i < 80; i++) {
            RoundResult special = core.generateSpecial(1, bs, start);
            if (special.mode() == RoundMode.WILD_VORTEX) {
                vortex = special;
                break;
            }
        }
        assertTrue(withWild != null && vortex != null);
        RoundResult ordinary = withWild;
        RoundResult tail = vortex;
        RedisRoundStore store = new RedisRoundStore() {
            @Override public RoundResult claim(BigDecimal betSize, int bl, BigDecimal startingBalance,
                                               ResultUtil.EnergyState energy) {
                return core.compose(ordinary.candidate(), energy, tail.candidate(), bl, betSize, startingBalance);
            }
        };
        PlayerSession session = new SessionService(new BigDecimal("10000.00"), store).verify("launch");
        for (int i = 0; i < GameRules.WILD_ENERGY_CAP - 1; i++) {
            playRound(session);
            assertTrue(((Number) session.last().get("wn")).intValue() > 0);
            assertTrue(((Number) session.last().get("rsn")).intValue() == 0);
        }
        playRound(session);
        assertEquals(3, ((Number) session.last().get("rsn")).intValue());
        assertEquals(3, ((Number) session.last().get("nrsc")).intValue());
        assertEquals(0, ((Number) session.last().get("wn")).intValue());
    }

    private static void playRound(PlayerSession session) {
        session.spin(new BigDecimal("0.02"), 1, null);
        int steps = 1;
        while (((Number) session.last().get("ss")).intValue() != 1
                || ((Number) session.last().get("fsn")).intValue() != ((Number) session.last().get("nfsc")).intValue()
                || ((Number) session.last().get("rsn")).intValue() != ((Number) session.last().get("nrsc")).intValue()) {
            session.spin(new BigDecimal("0.02"), 1, null);
            steps++;
            if (steps > 80) throw new IllegalStateException("round did not terminate");
        }
    }
}
