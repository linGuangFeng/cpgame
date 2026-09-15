package com.hd.pg.appapi.business.vo.cpgame.blessing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlessingResultUtilTest {
    @Test
    void capturedFireMidWinIs50() {
        int[] p = {3, 2, 3, 3, 2, 1, 1, 2, 1};
        assertEquals(50, BlessingResultUtil.evaluateOdd(p));
    }

    @Test
    void capturedFireHighWinIs100() {
        int[] p = {3, 1, 3, 2, 1, 2, 3, 1, 2};
        assertEquals(100, BlessingResultUtil.evaluateOdd(p));
    }

    @Test
    void capturedAnyWinIs5() {
        int[] p = {1, 2, 1, 1, 2, 2, 3, 3, 3};
        assertEquals(5, BlessingResultUtil.evaluateOdd(p));
    }

    @Test
    void capturedLowWinIs25() {
        int[] p = {1, 3, 1, 2, 3, 2, 3, 3, 1};
        assertEquals(25, BlessingResultUtil.evaluateOdd(p));
    }

    @Test
    void zeroInMiddleIsLoss() {
        int[] p = {1, 0, 3, 2, 2, 1, 2, 0, 2};
        assertEquals(0, BlessingResultUtil.evaluateOdd(p));
    }

    @Test
    void catalogOddsMatchEvaluator() {
        BlessingMultiplierCatalog.singleReelOdds().forEach((odd, middles) -> {
            for (int[] mid : middles) {
                int[] p = BlessingMultiplierCatalog.boardFromMiddle(mid);
                assertEquals(odd, BlessingResultUtil.evaluateOdd(p), java.util.Arrays.toString(mid));
            }
        });
    }

    @Test
    void dualBothWinDoublesSum() {
        SecureRandom random = new SecureRandom();
        BlessingRoundFactory.BlessingRound round = BlessingRoundFactory.generate(
                random, 3, new BigDecimal("0.5"), 1, 105);
        assertEquals(new BigDecimal("105.00"), round.totalWin());
        assertEquals(2, round.bothMultiplier());
        assertEquals(new BigDecimal("105.0000"), round.odds());
        assertEquals(105, round.top().odd() + round.bottom().odd());
    }

    @Test
    void tenThousandRealtimeRoundsPassOracle() {
        SecureRandom random = new SecureRandom();
        int wins = 0;
        for (int i = 0; i < 10_000; i++) {
            int type = 1 + random.nextInt(3);
            int requested = BlessingRoundFactory.sampleRequestedOdd(random, type);
            BlessingRoundFactory.BlessingRound round = BlessingRoundFactory.generate(
                    random, type, new BigDecimal("0.5"), 1, requested);
            int top = BlessingResultUtil.evaluateOdd(round.top().p());
            int bottom = BlessingResultUtil.evaluateOdd(round.bottom().p());
            if (type == 1) assertEquals(round.top().odd(), top);
            if (type == 2) assertEquals(round.bottom().odd(), bottom);
            if (round.totalWin().signum() > 0) wins++;
            assertTrue(round.charged().signum() > 0);
        }
        assertTrue(wins > 100, "expected some wins, got " + wins);
    }
}
