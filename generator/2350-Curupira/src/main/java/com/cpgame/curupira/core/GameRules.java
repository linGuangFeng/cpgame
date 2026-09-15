package com.cpgame.curupira.core;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GameRules {
    public static final int COLUMNS = 5;
    public static final int ROWS = 3;
    public static final int CELL_COUNT = 15;
    public static final int PAYLINE_COUNT = 25;
    public static final int WILD = 21;
    public static final int SCATTER = 31;
    public static final int SCATTER_TRIGGER = 3;
    public static final int FREE_EXPANDING_WILD_COUNT = 6;
    public static final int HOLD_START_SPINS = 3;
    public static final int COIN_TOTAL_COUNT = 15;
    public static final int COIN_MAX = 10;
    public static final int BUY_FREE_MULTIPLE = 40;
    public static final int HOLD_FCA = 7;
    public static final String ORDINARY_POOL = "ORDINARY_PAID";
    public static final String SPECIAL_POOL = "SPECIAL";
    public static final BigDecimal MINIMUM_LINE_BET = new BigDecimal("0.02");
    public static final Set<Integer> SYMBOLS = Set.of(1, 2, 3, 4, 11, 12, 13, 14, WILD, SCATTER);
    public static final List<Integer> NON_SPECIAL_SYMBOLS = List.of(1, 2, 3, 4, 11, 12, 13, 14);
    /** Curupira 的首轴不允许生成 Wild；Scatter 仍按当前符号权重参与。 */
    public static final List<Integer> FIRST_REEL_SYMBOLS = SYMBOLS.stream()
            .filter(symbol -> symbol != WILD)
            .sorted()
            .toList();

    public static final int[][] PAYLINES = {
            {1,1,1,1,1}, {2,2,2,2,2}, {0,0,0,0,0}, {2,1,0,1,2}, {0,1,2,1,0},
            {1,2,2,2,1}, {1,0,0,0,1}, {2,2,1,0,0}, {0,0,1,2,2}, {1,0,1,2,1},
            {1,2,1,0,1}, {2,1,1,1,2}, {0,1,1,1,0}, {2,1,2,1,2}, {0,1,0,1,0},
            {1,1,2,1,1}, {1,1,0,1,1}, {2,2,0,2,2}, {0,0,2,0,0}, {2,0,0,0,2},
            {0,2,2,2,0}, {1,0,2,0,1}, {1,2,0,2,1}, {2,0,2,0,2}, {0,2,0,2,0}
    };

    public static final Map<Integer, Map<Integer, Integer>> PAYOUTS = Map.of(
            WILD, Map.of(3, 500, 4, 1000, 5, 3000),
            1, Map.of(3, 50, 4, 80, 5, 120), 2, Map.of(3, 20, 4, 50, 5, 80),
            3, Map.of(3, 15, 4, 30, 5, 60), 4, Map.of(3, 10, 4, 25, 5, 50),
            11, Map.of(3, 8, 4, 10, 5, 15), 12, Map.of(3, 4, 4, 8, 5, 12),
            13, Map.of(3, 2, 4, 5, 5, 8), 14, Map.of(3, 1, 4, 3, 5, 5));

    /** 每列最多 1 个 Scatter；原厂抓包未见同轴双 Scatter。 */
    public static List<Integer> atMostOneScatterPerColumn(List<Integer> board) {
        int[] seen = new int[COLUMNS];
        List<Integer> copy = new java.util.ArrayList<>(board);
        for (int i = 0; i < copy.size(); i++) {
            if (copy.get(i) != SCATTER) continue;
            int column = i / ROWS;
            seen[column]++;
            if (seen[column] > 1) {
                copy.set(i, NON_SPECIAL_SYMBOLS.get(column % NON_SPECIAL_SYMBOLS.size()));
            }
        }
        return List.copyOf(copy);
    }

    private GameRules() {}
}
