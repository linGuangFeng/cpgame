package com.cpgame.sambasensation.core;

import java.util.List;

/**
 * 4900训练局反推的逐列、逐轴、整页硬上限；任何超限候选整局丢弃。
 * Wild(symbol 0)经验频率：betType1轴1为1537/35670≈4.3098%，列/轴/页上限3/8/8；
 * betType2两轴约3.0667%/4.2%，整页上限9；betType3三轴约2.5637%/2.4205%/2.649%，整页上限10。
 * Free入口的outer/big分开使用790页证据权重，Wild整页最多12；精确分子分母见generator.properties。
 */
public final class BoardCaps {
    private BoardCaps() { }

    private static final int[][][][] PAID_AXIS = {
            {{{8,11,13,10,8,8,8,8,8,8,4},{3,3,3,3,3,3,3,3,3,3,3}}},
            {{{5,8,6,6,6,5,6,6,6,5,2},{3,3,3,3,3,3,3,3,3,3,2}},
             {{7,12,6,7,5,5,7,6,4,7,4},{3,3,3,3,3,3,3,3,3,3,1}}},
            {{{6,12,11,9,8,8,9,8,7,9,6},{3,3,3,3,3,3,3,3,3,3,3}},
             {{8,13,13,11,7,8,8,8,7,8,4},{3,3,3,3,3,3,3,3,3,3,2}},
             {{8,12,11,7,8,7,7,7,7,8,5},{3,3,3,3,3,3,3,3,3,3,3}}}
    };
    private static final int[][] PAID_PAGE = {
            {8,11,13,10,8,8,8,8,8,8,4}, {9,12,9,8,7,7,9,8,8,11,5}, {10,18,17,14,14,15,15,14,14,14,8}
    };
    private static final int[][][] FREE_AXIS = {
            {{11,13,14,12,14,13,12,13,13,13,0},{3,3,3,3,3,3,3,3,3,3,0}},
            {{10,12,13,14,12,15,13,12,12,12,0},{3,3,3,3,3,3,3,3,3,3,0}},
            {{11,13,14,12,12,12,12,12,12,13,0},{3,3,3,3,3,3,3,3,3,3,0}}
    };
    private static final int[] FREE_PAGE = {12,23,25,22,26,23,31,31,29,32,0};
    private static final int[][][] BUY_AXIS = {
            {{0,0,0,2,1,1,2,0,0,0,9},{0,0,0,1,1,1,1,0,0,0,3}},
            {{0,0,1,0,0,0,0,1,2,1,10},{0,0,1,0,0,0,0,1,1,1,3}},
            {{0,0,1,0,3,0,0,0,0,0,11},{0,0,1,0,1,0,0,0,0,0,3}}
    };
    private static final int[] BUY_PAGE = {0,0,2,2,4,1,2,1,2,1,30};
    private static final int[][][] SCATTER_ALLOWED = {
            {{0,1,2,3,4,5,6,7,8,9,10,11,12,13,14}},
            {{4,8,9,10,13,14},{0,2,6,7,8,9,10,12,14}},
            {{0,1,2,3,4,5,6,7,8,9,10,11,12,13,14},{0,1,2,3,4,5,6,7,8,9,10,11,12,13,14},{0,1,2,3,4,5,6,7,8,9,10,11,12,13,14}}
    };
    public static final int[][] BUY_SCATTER_POSITIONS = {{2,3,4,5,7,8,9,11,13},{0,1,2,4,5,7,8,9,10,11},{0,1,2,3,4,5,7,9,10,12,13}};

    public static void validatePaid(int betType, List<int[]> boards) {
        if (boards.size() != betType) throw new IllegalArgumentException("paid page axis count mismatch");
        for (int axis = 0; axis < boards.size(); axis++) validateBoard(boards.get(axis), PAID_AXIS[betType - 1][axis][0], PAID_AXIS[betType - 1][axis][1], SCATTER_ALLOWED[betType - 1][axis]);
        validatePage(boards, PAID_PAGE[betType - 1]);
    }

    public static void validateFree(List<int[]> boards) {
        if (boards.size() != 3) throw new IllegalArgumentException("free page must contain three axes");
        for (int axis = 0; axis < 3; axis++) validateBoard(boards.get(axis), FREE_AXIS[axis][0], FREE_AXIS[axis][1], new int[0]);
        validatePage(boards, FREE_PAGE);
    }

    public static void validateFeatureBuy(List<int[]> boards) {
        if (boards.size() != 3) throw new IllegalArgumentException("feature-buy page must contain three axes");
        for (int axis = 0; axis < 3; axis++) validateBoard(boards.get(axis), BUY_AXIS[axis][0], BUY_AXIS[axis][1], BUY_SCATTER_POSITIONS[axis]);
        validatePage(boards, BUY_PAGE);
    }

    public static void validateCompleteFact(GameRuleCore.CompleteRoundFact fact) {
        List<int[]> initial = fact.steps().get(0).boards();
        if (fact.entryKind() == GameRuleCore.EntryKind.PAID_INITIAL) validatePaid(fact.betType(), initial);
        if (fact.entryKind() == GameRuleCore.EntryKind.FEATURE_BUY_INITIAL) validateFeatureBuy(initial);
        for (int step = 1; step < fact.steps().size(); step++) validateFree(fact.steps().get(step).boards());
    }

    private static void validateBoard(int[] board, int[] totalCaps, int[] columnCaps, int[] allowedScatter) {
        int[] totals = new int[11];
        boolean[] scatterAllowed = new boolean[GameRuleCore.CELLS];
        for (int position : allowedScatter) scatterAllowed[position] = true;
        for (int position = 0; position < board.length; position++) {
            int symbol = board[position];
            totals[symbol]++;
            if (symbol == GameRuleCore.SCATTER && !scatterAllowed[position]) throw new IllegalArgumentException("Scatter position was not observed for this entry");
        }
        for (int symbol = 0; symbol <= 10; symbol++) if (totals[symbol] > totalCaps[symbol]) throw new IllegalArgumentException("axis symbol cap exceeded: " + symbol);
        for (int column = 0; column < 5; column++) {
            int[] counts = new int[11];
            for (int row = 0; row < 3; row++) counts[board[row * 5 + column]]++;
            for (int symbol = 0; symbol <= 10; symbol++) if (counts[symbol] > columnCaps[symbol]) throw new IllegalArgumentException("column symbol cap exceeded: " + symbol);
        }
    }

    private static void validatePage(List<int[]> boards, int[] caps) {
        int[] totals = new int[11];
        for (int[] board : boards) for (int symbol : board) totals[symbol]++;
        for (int symbol = 0; symbol <= 10; symbol++) if (totals[symbol] > caps[symbol]) throw new IllegalArgumentException("page symbol cap exceeded: " + symbol);
    }
}
