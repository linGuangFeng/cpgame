package com.cpgame.hiddenrealm.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Independent payout oracle. Reimplements orthogonal cluster flood-fill from the help text
 * and captured win_array rows. Does not call GameRuleCore.evaluate.
 */
public final class ResultUtil {
    public enum Outcome { NORMAL_LOSS, NORMAL_WIN, SPECIAL }

    public record Analysis(Outcome outcome, int oddsSum, int integerMultiplier, int deliveries, int pages) {}

    private static final int[][] HELP_PAY = {
            {},
            {0, 0, 0, 0, 2, 3, 4, 6, 8, 10, 15, 15, 15, 20, 20, 40, 40, 40, 100, 100, 100, 200, 200, 200, 200, 300},
            {0, 0, 0, 0, 3, 5, 6, 10, 15, 20, 30, 30, 30, 40, 40, 60, 60, 60, 200, 200, 200, 300, 300, 300, 300, 400},
            {0, 0, 0, 0, 4, 6, 9, 15, 20, 30, 40, 40, 40, 50, 50, 80, 80, 80, 300, 300, 300, 400, 400, 400, 400, 500},
            {0, 0, 0, 0, 5, 10, 15, 20, 30, 40, 50, 50, 50, 60, 60, 100, 100, 100, 500, 500, 500, 600, 600, 600, 600, 600},
            {0, 0, 0, 0, 10, 15, 30, 60, 70, 80, 100, 100, 100, 300, 300, 400, 400, 400, 600, 600, 600, 800, 800, 800, 800, 800},
            {0, 0, 0, 0, 15, 20, 40, 70, 80, 100, 200, 200, 200, 400, 400, 500, 500, 500, 800, 800, 800, 1000, 1000, 1000, 1000, 1000},
            {0, 0, 0, 0, 20, 30, 50, 80, 100, 200, 300, 300, 300, 600, 600, 800, 800, 800, 1000, 1000, 1000, 2000, 2000, 2000, 2000, 5000},
            {0, 0, 0, 0, 30, 40, 70, 100, 200, 300, 500, 500, 500, 1000, 1000, 2000, 2000, 2000, 5000, 5000, 5000, 10000, 10000, 10000, 10000, 20000}
    };

    public ResultUtil(GameRuleCore rules) {
        Objects.requireNonNull(rules);
    }

    public int pageOdds(int[][] board, int typeSkill) {
        if (typeSkill == 4 && containsLow(board)) return 0;
        return clusterOdds(board);
    }

    public int clusterOdds(int[][] board) {
        if (board == null || board.length != 5) throw new IllegalArgumentException("oracle board");
        int sum = 0;
        for (int symbol = 1; symbol <= 8; symbol++) {
            boolean[][] seen = new boolean[5][5];
            for (int col = 0; col < 5; col++) {
                for (int row = 0; row < 5; row++) {
                    if (seen[col][row] || board[col][row] != symbol) continue;
                    int size = walk(board, seen, col, row, symbol);
                    if (size >= 4) sum += HELP_PAY[symbol][size];
                }
            }
        }
        return sum;
    }

    private int walk(int[][] board, boolean[][] seen, int col, int row, int symbol) {
        ArrayDeque<int[]> q = new ArrayDeque<>();
        seen[col][row] = true;
        q.add(new int[] {col, row});
        int size = 0;
        while (!q.isEmpty()) {
            int[] at = q.removeFirst();
            size++;
            int x = at[0], y = at[1];
            tryNeighbor(board, seen, q, x + 1, y, symbol);
            tryNeighbor(board, seen, q, x - 1, y, symbol);
            tryNeighbor(board, seen, q, x, y + 1, symbol);
            tryNeighbor(board, seen, q, x, y - 1, symbol);
        }
        return size;
    }

    private void tryNeighbor(int[][] board, boolean[][] seen, ArrayDeque<int[]> q, int x, int y, int symbol) {
        if (x < 0 || x >= 5 || y < 0 || y >= 5 || seen[x][y]) return;
        int v = board[x][y];
        if (v != symbol && v != 9) return;
        seen[x][y] = true;
        q.addLast(new int[] {x, y});
    }

    private boolean containsLow(int[][] board) {
        for (int c = 0; c < 5; c++) for (int r = 0; r < 5; r++) if (board[c][r] >= 1 && board[c][r] <= 4) return true;
        return false;
    }

    public Analysis analyze(CompleteRound round) {
        int odds = 0, pages = 0;
        for (CompleteRound.Delivery delivery : round.deliveries()) {
            for (CompleteRound.Page page : delivery.pages()) {
                odds += pageOdds(page.board(), delivery.typeSkill());
                pages++;
            }
        }
        Outcome outcome = round.maxPhase() > 0
                ? Outcome.SPECIAL
                : (odds == 0 ? Outcome.NORMAL_LOSS : Outcome.NORMAL_WIN);
        return new Analysis(outcome, odds, odds, round.deliveries().size(), pages);
    }

    public BigDecimal money(int odds, BigDecimal betSize, int level) {
        return BigDecimal.valueOf(odds).multiply(betSize).multiply(BigDecimal.valueOf(level)).setScale(2, RoundingMode.HALF_UP);
    }
}
