package com.cpgame.hiddenrealm.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Single Hidden Realm 1380 rule core.
 * Evidence: Game1380 index.5513c.js, initRoom prop_odds, origin-gap-fill true paid rounds.
 */
public final class GameRuleCore {
    public static final int GAME_ID = 1380;
    public static final int COLS = 5;
    public static final int ROWS = 5;
    public static final int WILD = 9;
    public static final int LINE_COUNT = 10;
    public static final int GRASS_AT = 10;
    public static final int WATER_AT = 30;
    public static final int FIRE_AT = 50;
    public static final int LORD_AT = 70;
    public static final String RULES_VERSION = "1380-rules-v2";
    public static final String RULES_HASH = "a47cbb826fa130d6cf03c208a992f29789aad2a9870202ea7080145935f2d7db";
    public static final int[][] WATER_CELLS = {{1, 1}, {1, 3}, {3, 1}, {3, 3}};

    /** initRoom prop_odds: symbol 1..8, cluster size 4..25. */
    static final int[][] PAY = {
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

    public record Cell(int col, int row) {}
    public record Cluster(int symbol, List<Cell> cells, int odds) {
        public Cluster { cells = List.copyOf(cells); }
    }
    public record Evaluation(List<Cluster> clusters, boolean[][] win, int exploded, int oddsSum) {
        public Evaluation { clusters = List.copyOf(clusters); }
        public boolean scoring() { return oddsSum > 0; }
    }

    public void validateBoard(int[][] board) {
        if (board == null || board.length != COLS) throw new IllegalArgumentException("5 columns");
        for (int[] col : board) {
            if (col == null || col.length != ROWS) throw new IllegalArgumentException("5 rows");
            for (int s : col) if (s < 1 || s > WILD) throw new IllegalArgumentException("symbol " + s);
        }
    }

    public int[][] copy(int[][] board) {
        validateBoard(board);
        int[][] out = new int[COLS][ROWS];
        for (int c = 0; c < COLS; c++) out[c] = board[c].clone();
        return out;
    }

    public boolean hasLow(int[][] board) {
        validateBoard(board);
        for (int c = 0; c < COLS; c++) for (int r = 0; r < ROWS; r++) if (board[c][r] <= 4) return true;
        return false;
    }

    public int wildCount(int[][] board) {
        int n = 0;
        for (int c = 0; c < COLS; c++) for (int r = 0; r < ROWS; r++) if (board[c][r] == WILD) n++;
        return n;
    }

    public int wildsInColumn(int[][] board, int col) {
        int n = 0;
        for (int r = 0; r < ROWS; r++) if (board[col][r] == WILD) n++;
        return n;
    }

    /**
     * Orthogonal clusters of size &gt;= 4. Flood starts on a real symbol cell; wild substitutes
     * and is counted. A wild may belong to more than one symbol cluster. Wild-only groups do not pay.
     * Holdout match: 3665/3670 pages; remaining 5 are Dragon Lord no-low overlays with empty win_array.
     */
    public Evaluation evaluate(int[][] board) {
        validateBoard(board);
        boolean[][] win = new boolean[COLS][ROWS];
        List<Cluster> clusters = new ArrayList<>();
        int oddsSum = 0;
        for (int symbol = 1; symbol <= 8; symbol++) {
            boolean[][] visited = new boolean[COLS][ROWS];
            for (int c = 0; c < COLS; c++) {
                for (int r = 0; r < ROWS; r++) {
                    if (visited[c][r] || board[c][r] != symbol) continue;
                    List<Cell> cells = flood(board, visited, c, r, symbol);
                    if (cells.size() < 4) continue;
                    int odds = PAY[symbol][cells.size()];
                    clusters.add(new Cluster(symbol, cells, odds));
                    oddsSum += odds;
                    for (Cell cell : cells) win[cell.col][cell.row] = true;
                }
            }
        }
        int exploded = 0;
        for (int c = 0; c < COLS; c++) for (int r = 0; r < ROWS; r++) if (win[c][r]) exploded++;
        return new Evaluation(clusters, win, exploded, oddsSum);
    }

    public Evaluation evaluatePage(int[][] board, int typeSkill) {
        if (typeSkill == 4 && hasLow(board)) {
            validateBoard(board);
            return new Evaluation(List.of(), new boolean[COLS][ROWS], 0, 0);
        }
        return evaluate(board);
    }

    private List<Cell> flood(int[][] board, boolean[][] visited, int sc, int sr, int symbol) {
        ArrayDeque<Cell> q = new ArrayDeque<>();
        List<Cell> cells = new ArrayList<>();
        visited[sc][sr] = true;
        q.add(new Cell(sc, sr));
        while (!q.isEmpty()) {
            Cell here = q.removeFirst();
            cells.add(here);
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nc = here.col + d[0], nr = here.row + d[1];
                if (nc < 0 || nc >= COLS || nr < 0 || nr >= ROWS || visited[nc][nr]) continue;
                int v = board[nc][nr];
                if (v == symbol || v == WILD) {
                    visited[nc][nr] = true;
                    q.addLast(new Cell(nc, nr));
                }
            }
        }
        return cells;
    }

    public BigDecimal clusterAmount(int odds, BigDecimal betSize, int level) {
        return BigDecimal.valueOf(odds).multiply(betSize).multiply(BigDecimal.valueOf(level)).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal chargedBet(BigDecimal betSize, int level) {
        return betSize.multiply(BigDecimal.valueOf(level)).multiply(BigDecimal.valueOf(LINE_COUNT)).setScale(2, RoundingMode.HALF_UP);
    }

    public int maxPhase(int collection) {
        if (collection >= LORD_AT) return 4;
        if (collection >= FIRE_AT) return 3;
        if (collection >= WATER_AT) return 2;
        if (collection >= GRASS_AT) return 1;
        return 0;
    }

    public boolean deliveryTerminal(int typeSkill, int maxPhase) {
        if (typeSkill < 0 || maxPhase > 4 || typeSkill > maxPhase) throw new IllegalArgumentException("phase");
        return typeSkill == maxPhase;
    }

    public int[][] gravity(int[][] board, boolean[][] win) {
        validateBoard(board);
        int[][] next = new int[COLS][ROWS];
        for (int c = 0; c < COLS; c++) {
            int n = 0;
            for (int r = 0; r < ROWS; r++) if (!win[c][r]) next[c][n++] = board[c][r];
            while (n < ROWS) next[c][n++] = 0;
        }
        return next;
    }

    public int[][] applyGrass(int[][] board) {
        validateBoard(board);
        int[][] next = new int[COLS][ROWS];
        for (int c = 0; c < COLS; c++) {
            int n = 0;
            for (int r = 0; r < ROWS; r++) if (board[c][r] > 4) next[c][n++] = board[c][r];
            while (n < ROWS) next[c][n++] = 0;
        }
        return next;
    }

    public int[][] applyWater(int[][] board) {
        int[][] out = copy(board);
        for (int[] cell : WATER_CELLS) out[cell[0]][cell[1]] = WILD;
        return out;
    }

    public int[][] applyFire(int[][] board, int selected) {
        if (selected < 1 || selected > 8) throw new IllegalArgumentException("fire symbol");
        int[][] out = copy(board);
        for (int c = 0; c < COLS; c++) for (int r = 0; r < ROWS; r++)
            if (((c + r) & 1) == 0 && out[c][r] != WILD) out[c][r] = selected;
        return out;
    }

    public int emptyCount(int[][] board) {
        int n = 0;
        for (int c = 0; c < COLS; c++) for (int r = 0; r < ROWS; r++) if (board[c][r] == 0) n++;
        return n;
    }

    public void validateRound(CompleteRound round) {
        if (round.deliveries().isEmpty()) throw new IllegalArgumentException("empty round");
        CompleteRound.Delivery first = round.deliveries().get(0);
        if (first.type() != 1 || first.typeSkill() != 0) throw new IllegalArgumentException("paid spin");
        int prevCollection = 0;
        for (int d = 0; d < round.deliveries().size(); d++) {
            CompleteRound.Delivery delivery = round.deliveries().get(d);
            if (d > 0 && delivery.type() != 2) throw new IllegalArgumentException("continuation type");
            if (delivery.pages().isEmpty()) throw new IllegalArgumentException("empty delivery");
            int exploded = 0;
            for (CompleteRound.Page page : delivery.pages()) {
                validateBoard(page.board());
                exploded += evaluatePage(page.board(), delivery.typeSkill()).exploded();
            }
            if (delivery.collection() < exploded) throw new IllegalArgumentException("collection");
            if (delivery.collection() < prevCollection) throw new IllegalArgumentException("collection dropped");
            prevCollection = delivery.collection();
            boolean last = d == round.deliveries().size() - 1;
            if (deliveryTerminal(delivery.typeSkill(), delivery.maxPhase()) != last)
                throw new IllegalArgumentException("round boundary");
        }
    }
}
