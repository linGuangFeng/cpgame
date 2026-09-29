package com.hd.cpgame.jungleparty;

import java.math.BigDecimal;
import java.security.SecureRandom;

/**
 * Constructed ordinary win. The capture WIN kernels in generation-model-v37.tsv are all
 * large hits (about 50x total bet and up), so Redis never got 1x–20x buckets from sampling.
 */
final class IndependentWin {
    private static final BigDecimal BET = new BigDecimal("0.02");
    /** Line-pay units; 25 units = 1x total bet. Keep constructed ordinary wins in the small/medium range. */
    private static final int MAX_LINE_UNITS = 500;

    private IndependentWin() {}

    static GameRuleCore.Board board(SecureRandom random) {
        return POOL.generate(() -> {
            for (int i = 0; i < 20; i++) {
                GameRuleCore.Board board = candidate(random);
                if (valid(board)) return board;
            }
            return null;
        }, random::nextInt);
    }

    static GameRuleCore.Board candidate(SecureRandom random) {
        GameRuleCore.Board lossy = IndependentLoss.candidate(random);
        GameRuleCore.Symbol[] cells = lossy.cells();
        int line = random.nextInt(GameRuleCore.LINE_ROWS.length);
        int[] rows = GameRuleCore.LINE_ROWS[line];
        GameRuleCore.Symbol[] pays = {
                GameRuleCore.Symbol.N9, GameRuleCore.Symbol.A, GameRuleCore.Symbol.H1, GameRuleCore.Symbol.H2,
                GameRuleCore.Symbol.H3, GameRuleCore.Symbol.H4, GameRuleCore.Symbol.H5, GameRuleCore.Symbol.J,
                GameRuleCore.Symbol.K, GameRuleCore.Symbol.Q, GameRuleCore.Symbol.T};
        GameRuleCore.Symbol symbol = pays[random.nextInt(pays.length)];
        int minLen = (symbol == GameRuleCore.Symbol.H1 || symbol == GameRuleCore.Symbol.H2) ? 2 : 3;
        int length = minLen + random.nextInt(6 - minLen);
        if (length > 5) length = 5;
        for (int reel = 0; reel < length; reel++) cells[reel * GameRuleCore.ROWS + rows[reel]] = symbol;
        if (length < 5) {
            int stopIndex = length * GameRuleCore.ROWS + rows[length];
            GameRuleCore.Symbol stop = cells[stopIndex];
            if (stop == symbol || stop == GameRuleCore.Symbol.Wild) {
                GameRuleCore.Symbol replacement = pays[random.nextInt(pays.length)];
                if (replacement == symbol) replacement = GameRuleCore.Symbol.N9 == symbol ? GameRuleCore.Symbol.A : GameRuleCore.Symbol.N9;
                cells[stopIndex] = replacement;
            }
        }
        return new GameRuleCore.Board(cells);
    }

    private static boolean valid(GameRuleCore.Board board) {
        long scatters = 0;
        for (GameRuleCore.Symbol cell : board.cells()) if (cell == GameRuleCore.Symbol.Scat) scatters++;
        if (scatters >= 3) return false;
        GameRuleCore.Evaluation evaluation = GameRuleCore.evaluate(board, 1, BET, 0);
        if (evaluation.award().signum() <= 0) return false;
        int units = evaluation.award().divide(BET, 0, java.math.RoundingMode.UNNECESSARY).intValueExact();
        return units >= 1 && units <= MAX_LINE_UNITS;
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ZeroLossSupport<GameRuleCore.Board> POOL = new ZeroLossSupport<>(
            () -> {
                for (int i = 0; i < 40; i++) {
                    GameRuleCore.Board board = candidate(RANDOM);
                    if (valid(board)) return board;
                }
                throw new IllegalStateException("could not construct a small ordinary win");
            },
            IndependentWin::valid,
            board -> board);
}
