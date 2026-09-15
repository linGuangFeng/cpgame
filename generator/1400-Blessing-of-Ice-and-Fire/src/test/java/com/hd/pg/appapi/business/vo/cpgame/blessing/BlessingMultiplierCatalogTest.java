package com.hd.pg.appapi.business.vo.cpgame.blessing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlessingMultiplierCatalogTest {
    @Test
    void listAndMapSizes() {
        assertEquals(5, BlessingMultiplierCatalog.oddsList(1).size());
        assertEquals(5, BlessingMultiplierCatalog.oddsList(2).size());
        assertEquals(13, BlessingMultiplierCatalog.oddsList(3).size());
        assertEquals(5, BlessingMultiplierCatalog.comboMap(1).size());
        assertEquals(5, BlessingMultiplierCatalog.comboMap(2).size());
        assertEquals(13, BlessingMultiplierCatalog.comboMap(3).size());
        assertEquals(5, BlessingMultiplierCatalog.comboCount(1));
        assertEquals(5, BlessingMultiplierCatalog.comboCount(2));
        assertEquals(25, BlessingMultiplierCatalog.comboCount(3));
    }

    @Test
    void floorPicksGreatestNotAboveRequested() {
        assertEquals(25, BlessingMultiplierCatalog.floorOdd(1, 30));
        assertEquals(30, BlessingMultiplierCatalog.floorOdd(3, 30));
        assertEquals(25, BlessingMultiplierCatalog.floorOdd(3, 29));
        assertEquals(5, BlessingMultiplierCatalog.floorOdd(1, 7));
        assertEquals(0, BlessingMultiplierCatalog.floorOdd(1, 3));
        assertEquals(0, BlessingMultiplierCatalog.floorOdd(3, -10));
        assertEquals(100, BlessingMultiplierCatalog.floorOdd(1, 999));
        assertEquals(200, BlessingMultiplierCatalog.floorOdd(3, 999));
        assertEquals(10, BlessingMultiplierCatalog.floorOdd(3, 11));
    }

    @Test
    void bothThirtyIsFiveAndTwentyFive() {
        List<int[]> combos = BlessingMultiplierCatalog.combos(3, 30);
        assertEquals(2, combos.size());
        assertTrue(containsPair(combos, 5, 25));
        assertTrue(containsPair(combos, 25, 5));
    }

    @Test
    void generateThirtyBothUsesMappedCombo() {
        SecureRandom random = new SecureRandom();
        BlessingRoundFactory.BlessingRound round = BlessingRoundFactory.generate(
                random, 3, new BigDecimal("0.5"), 1, 30);
        int top = round.top().odd();
        int bottom = round.bottom().odd();
        assertEquals(30, top + bottom);
        assertTrue((top == 5 && bottom == 25) || (top == 25 && bottom == 5),
                top + "+" + bottom);
        assertEquals(2, round.bothMultiplier());
        assertEquals(new BigDecimal("30.00"), round.totalWin());
    }

    @Test
    void generateThirtyFireFloorsToTwentyFive() {
        SecureRandom random = new SecureRandom();
        BlessingRoundFactory.BlessingRound round = BlessingRoundFactory.generate(
                random, 1, new BigDecimal("0.5"), 1, 30);
        assertEquals(25, round.top().odd());
        assertEquals(0, round.bottom().odd());
        assertEquals(new BigDecimal("12.50"), round.totalWin());
    }

    private static boolean containsPair(List<int[]> combos, int top, int bottom) {
        for (int[] pair : combos) {
            if (pair[0] == top && pair[1] == bottom) return true;
        }
        throw new AssertionError("missing " + top + "," + bottom + " in " + combos.stream()
                .map(Arrays::toString).toList());
    }
}
