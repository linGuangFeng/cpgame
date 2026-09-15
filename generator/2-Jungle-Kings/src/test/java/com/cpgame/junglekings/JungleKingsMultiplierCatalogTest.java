package com.cpgame.junglekings;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JungleKingsMultiplierCatalogTest {
    private static final List<String> SINGLE = List.of(GameRuleCore.CB_TOP);
    private static final List<String> BOTH = GameRuleCore.CHESSBOARDS;
    private static final IndependentVerifier VERIFIER = new IndependentVerifier();

    @Test
    void listAndMapSizes() {
        assertEquals(3, JungleKingsMultiplierCatalog.oddsList(SINGLE).size());
        assertEquals(3, JungleKingsMultiplierCatalog.oddsList(List.of(GameRuleCore.CB_BOTTOM)).size());
        assertEquals(6, JungleKingsMultiplierCatalog.oddsList(BOTH).size());
        assertEquals(3, JungleKingsMultiplierCatalog.comboMap(SINGLE).size());
        assertEquals(6, JungleKingsMultiplierCatalog.comboMap(BOTH).size());
        assertEquals(3, JungleKingsMultiplierCatalog.comboCount(SINGLE));
        assertEquals(9, JungleKingsMultiplierCatalog.comboCount(BOTH));
        assertEquals(List.of(0, 50, 100), JungleKingsMultiplierCatalog.oddsList(SINGLE));
        assertEquals(List.of(0, 25, 50, 100, 150, 200), JungleKingsMultiplierCatalog.oddsList(BOTH));
    }

    @Test
    void catalogBoardsMatchEvaluator() {
        for (int chessboard = 0; chessboard < 2; chessboard++) {
            JungleKingsMultiplierCatalog.reelOdds(chessboard).forEach((odd, triples) -> {
                assertTrue(triples.size() >= 1, "odd " + odd);
                for (List<String> reels : triples) {
                    assertEquals(odd, JungleKingsMultiplierCatalog.pageOddFromReels(reels), reels.toString());
                    List<String> board = GameRuleCore.expandBoard(reels);
                    assertEquals(odd, JungleKingsMultiplierCatalog.pageOdd(board));
                }
            });
            assertEquals(1, JungleKingsMultiplierCatalog.boardsForOdd(chessboard, 50).size());
            assertEquals(1, JungleKingsMultiplierCatalog.boardsForOdd(chessboard, 100).size());
            assertEquals(61, JungleKingsMultiplierCatalog.boardsForOdd(chessboard, 0).size());
        }
    }

    @Test
    void floorPicksGreatestNotAboveRequested() {
        assertEquals(0, JungleKingsMultiplierCatalog.floorOdd(SINGLE, 30));
        assertEquals(50, JungleKingsMultiplierCatalog.floorOdd(SINGLE, 50));
        assertEquals(50, JungleKingsMultiplierCatalog.floorOdd(SINGLE, 99));
        assertEquals(100, JungleKingsMultiplierCatalog.floorOdd(SINGLE, 100));
        assertEquals(100, JungleKingsMultiplierCatalog.floorOdd(SINGLE, 999));
        assertEquals(0, JungleKingsMultiplierCatalog.floorOdd(SINGLE, 3));
        assertEquals(0, JungleKingsMultiplierCatalog.floorOdd(BOTH, -10));
        assertEquals(0, JungleKingsMultiplierCatalog.floorOdd(BOTH, 24));
        assertEquals(25, JungleKingsMultiplierCatalog.floorOdd(BOTH, 25));
        assertEquals(25, JungleKingsMultiplierCatalog.floorOdd(BOTH, 30));
        assertEquals(25, JungleKingsMultiplierCatalog.floorOdd(BOTH, 49));
        assertEquals(50, JungleKingsMultiplierCatalog.floorOdd(BOTH, 50));
        assertEquals(150, JungleKingsMultiplierCatalog.floorOdd(BOTH, 175));
        assertEquals(200, JungleKingsMultiplierCatalog.floorOdd(BOTH, 999));
    }

    @Test
    void bothTwentyFiveIsFiftyAndZero() {
        List<int[]> combos = JungleKingsMultiplierCatalog.combos(BOTH, 25);
        assertEquals(2, combos.size());
        assertTrue(containsPair(combos, 50, 0));
        assertTrue(containsPair(combos, 0, 50));
    }

    @Test
    void bothOneFiftyIsFiftyAndOneHundred() {
        List<int[]> combos = JungleKingsMultiplierCatalog.combos(BOTH, 150);
        assertEquals(2, combos.size());
        assertTrue(containsPair(combos, 50, 100));
        assertTrue(containsPair(combos, 100, 50));
    }

    @Test
    void generateOneFiftyBothUsesMappedCombo() {
        SecureRandom random = new SecureRandom();
        CompleteRound round = CompleteRoundFactory.generate(
                random, BOTH, new BigDecimal("0.5"), 1, 150);
        VERIFIER.verify(round);
        int top = JungleKingsMultiplierCatalog.pageOdd(round.boards().get(0));
        int bottom = JungleKingsMultiplierCatalog.pageOdd(round.boards().get(1));
        assertEquals(150, round.multiplier());
        assertTrue((top == 50 && bottom == 100) || (top == 100 && bottom == 50), top + "+" + bottom);
        assertEquals(0, new BigDecimal("150").compareTo(round.winAmount()));
        assertEquals(List.of(GameRuleCore.PL_TOP, GameRuleCore.PL_BOTTOM), round.winPaylineKeys());
    }

    @Test
    void generateThirtyBothFloorsToTwentyFive() {
        SecureRandom random = new SecureRandom();
        CompleteRound round = CompleteRoundFactory.generate(
                random, BOTH, new BigDecimal("0.5"), 1, 30);
        VERIFIER.verify(round);
        assertEquals(25, round.multiplier());
        int top = JungleKingsMultiplierCatalog.pageOdd(round.boards().get(0));
        int bottom = JungleKingsMultiplierCatalog.pageOdd(round.boards().get(1));
        assertTrue((top == 50 && bottom == 0) || (top == 0 && bottom == 50), top + "+" + bottom);
        assertEquals(1, round.winPaylineKeys().size());
    }

    @Test
    void generateThirtySingleFloorsToZero() {
        SecureRandom random = new SecureRandom();
        CompleteRound round = CompleteRoundFactory.generate(
                random, SINGLE, new BigDecimal("0.5"), 1, 30);
        VERIFIER.verify(round);
        assertEquals(0, round.multiplier());
        assertEquals(RoundMode.LOSS, round.mode());
    }

    @Test
    void generateOneHundredSingleIsS00011() {
        SecureRandom random = new SecureRandom();
        CompleteRound round = CompleteRoundFactory.generate(
                random, SINGLE, new BigDecimal("0.5"), 1, 100);
        VERIFIER.verify(round);
        assertEquals(100, round.multiplier());
        assertEquals(List.of("S00011", "S00011", "S00011"), round.logicalReels(0));
        assertEquals(0, new BigDecimal("50").compareTo(round.winAmount()));
    }

    private static boolean containsPair(List<int[]> combos, int top, int bottom) {
        for (int[] pair : combos) {
            if (pair[0] == top && pair[1] == bottom) return true;
        }
        throw new AssertionError("missing " + top + "," + bottom + " in " + combos.stream()
                .map(Arrays::toString).toList());
    }
}
