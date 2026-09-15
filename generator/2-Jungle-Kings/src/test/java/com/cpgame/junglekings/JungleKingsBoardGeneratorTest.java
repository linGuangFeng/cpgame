package com.cpgame.junglekings;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JungleKingsBoardGeneratorTest {
    private static final IndependentVerifier VERIFIER = new IndependentVerifier();

    @Test
    void winBoardsAreConfirmedTriples() {
        SecureRandom random = new SecureRandom();
        for (int odd : new int[] {50, 100}) {
            for (int chessboard = 0; chessboard < 2; chessboard++) {
                for (int i = 0; i < 200; i++) {
                    List<String> board = JungleKingsBoardGenerator.generate(random, chessboard, odd);
                    assertEquals(odd, JungleKingsMultiplierCatalog.pageOdd(board));
                    JungleKingsBoardGenerator.requireOriginShape(board, odd);
                    List<String> reels = GameRuleCore.logicalReels(board);
                    assertEquals(reels.get(0), reels.get(1));
                    assertEquals(reels.get(0), reels.get(2));
                    assertTrue(GameRuleCore.confirmedWinSymbol(reels.get(0)));
                }
            }
        }
    }

    @Test
    void lossBoardsAreNeverConfirmedTriples() {
        SecureRandom random = new SecureRandom();
        for (int chessboard = 0; chessboard < 2; chessboard++) {
            for (int i = 0; i < 500; i++) {
                List<String> board = JungleKingsBoardGenerator.generate(random, chessboard, 0);
                assertEquals(0, JungleKingsMultiplierCatalog.pageOdd(board));
                JungleKingsBoardGenerator.requireOriginShape(board, 0);
                List<String> reels = GameRuleCore.logicalReels(board);
                boolean triple = reels.get(0).equals(reels.get(1)) && reels.get(0).equals(reels.get(2));
                if (triple) {
                    assertTrue(GameRuleCore.payMultiplier(reels.get(0)) <= 0, reels.toString());
                }
            }
        }
    }

    @Test
    void tenThousandRoundsKeepCatalogOdds() {
        SecureRandom random = new SecureRandom();
        CompleteRoundFactory factory = new CompleteRoundFactory();
        int wins = 0;
        for (int i = 0; i < 10_000; i++) {
            List<String> layout = GameRuleCore.LINE_LAYOUTS.get(random.nextInt(GameRuleCore.LINE_LAYOUTS.size()));
            int requested = JungleKingsMultiplierCatalog.sampleRequestedOdd(random, layout);
            CompleteRound round = CompleteRoundFactory.generate(
                    random, layout, new BigDecimal("0.5"), 1, requested);
            VERIFIER.verify(round);
            int floored = JungleKingsMultiplierCatalog.floorOdd(layout, requested);
            assertEquals(floored, round.multiplier());
            assertEquals(layout, round.chessboards());
            if (round.mode() == RoundMode.WIN) wins++;
            factory.generate(round.mode() == RoundMode.WIN ? RoundMode.WIN : RoundMode.LOSS,
                    layout, random, new BigDecimal("0.5"), 1);
        }
        assertTrue(wins > 100, "expected some wins, got " + wins);
    }
}
