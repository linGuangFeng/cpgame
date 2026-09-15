package com.cpgame.junglekings;

import java.security.SecureRandom;
import java.util.List;

/**
 * Picks one catalog reel triple for a page odd and expands it to the 9-cell window.
 * No retry: ResultUtil mismatch throws.
 */
public final class JungleKingsBoardGenerator {
    private JungleKingsBoardGenerator() { }

    public static List<String> generate(SecureRandom random, int chessboardIndex, int pageOdd) {
        List<List<String>> patterns = JungleKingsMultiplierCatalog.boardsForOdd(chessboardIndex, pageOdd);
        List<String> reels = patterns.get(random.nextInt(patterns.size()));
        List<String> board = GameRuleCore.expandBoard(reels);
        int got = JungleKingsMultiplierCatalog.pageOdd(board);
        if (got != pageOdd) {
            throw new IllegalStateException("generated page odd " + got + " != " + pageOdd);
        }
        requireOriginShape(board, pageOdd);
        return board;
    }

    public static List<String> idleLoss(SecureRandom random, int chessboardIndex) {
        return generate(random, chessboardIndex, 0);
    }

    public static void requireOriginShape(List<String> board, int pageOdd) {
        List<String> reels = GameRuleCore.logicalReels(board);
        GameRuleCore.requireConfirmedWinBoundary(reels);
        int got = JungleKingsMultiplierCatalog.pageOddFromReels(reels);
        if (got != pageOdd) {
            throw new IllegalStateException("board page odd " + got + " != " + pageOdd);
        }
        if (pageOdd > 0 && !GameRuleCore.confirmedWinSymbol(reels.get(0))) {
            throw new IllegalStateException("win board is not a confirmed triple");
        }
    }
}
