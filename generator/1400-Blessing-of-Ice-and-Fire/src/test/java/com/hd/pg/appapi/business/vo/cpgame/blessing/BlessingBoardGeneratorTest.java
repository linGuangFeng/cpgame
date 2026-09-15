package com.hd.pg.appapi.business.vo.cpgame.blessing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlessingBoardGeneratorTest {
    @Test
    void capturedOriginWinIsFullGrid() {
        int[] p = {2, 1, 2, 2, 1, 2, 3, 1, 3};
        assertEquals(100, BlessingResultUtil.evaluateOdd(p));
        BlessingBoardGenerator.requireOriginShape(p, 100);
    }

    @Test
    void capturedOriginLossZerosOnlyInMiddle() {
        int[] p = {1, 0, 3, 2, 3, 2, 3, 0, 3};
        assertEquals(0, BlessingResultUtil.evaluateOdd(p));
        BlessingBoardGenerator.requireOriginShape(p, 0);
    }

    @Test
    void winBoardsAreFullAlignedThreeByThree() {
        SecureRandom random = new SecureRandom();
        for (int odd : new int[] {5, 25, 50, 100}) {
            for (int i = 0; i < 200; i++) {
                int[] p = BlessingBoardGenerator.generate(random, odd);
                assertEquals(odd, BlessingResultUtil.evaluateOdd(p), Arrays.toString(p));
                BlessingBoardGenerator.requireOriginShape(p, odd);
                assertEquals(3, nonEmpty(p, 0));
                assertEquals(3, nonEmpty(p, 1));
                assertEquals(3, nonEmpty(p, 2));
            }
        }
    }

    @Test
    void lossZerosOnlyInMiddleNeverSingleSymbolColumn() {
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 500; i++) {
            int[] p = BlessingBoardGenerator.generate(random, 0);
            assertEquals(0, BlessingResultUtil.evaluateOdd(p), Arrays.toString(p));
            BlessingBoardGenerator.requireOriginShape(p, 0);
            for (int col = 0; col < 3; col++) {
                int n = nonEmpty(p, col);
                assertTrue(n == 2 || n == 3, "col " + col + " count " + n + " " + Arrays.toString(p));
            }
        }
    }

    @Test
    void tenThousandRoundsKeepOriginShape() {
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 10_000; i++) {
            int type = 1 + random.nextInt(3);
            int requested = BlessingRoundFactory.sampleRequestedOdd(random, type);
            BlessingRoundFactory.BlessingRound round = BlessingRoundFactory.generate(
                    random, type, new BigDecimal("0.5"), 1, requested);
            int topShape = BlessingResultUtil.evaluateOdd(round.top().p());
            int bottomShape = BlessingResultUtil.evaluateOdd(round.bottom().p());
            BlessingBoardGenerator.requireOriginShape(round.top().p(), topShape);
            BlessingBoardGenerator.requireOriginShape(round.bottom().p(), bottomShape);
            if (round.top().odd() > 0) {
                assertNoEmpty(round.top().p());
            }
            if (round.bottom().odd() > 0) {
                assertNoEmpty(round.bottom().p());
            }
        }
    }

    private static int nonEmpty(int[] p, int col) {
        int n = 0;
        for (int row = 0; row < 3; row++) {
            if (p[col * 3 + row] != BlessingResultUtil.EMPTY) n++;
        }
        return n;
    }

    private static void assertNoEmpty(int[] p) {
        for (int v : p) {
            assertTrue(v >= 1 && v <= 3, Arrays.toString(p));
        }
    }
}
