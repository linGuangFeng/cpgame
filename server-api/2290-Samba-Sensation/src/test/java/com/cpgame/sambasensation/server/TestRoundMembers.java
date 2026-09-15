package com.cpgame.sambasensation.server;

import com.cpgame.sambasensation.core.BoardCaps;
import com.cpgame.sambasensation.core.GameRuleCore;
import com.cpgame.sambasensation.core.MinimalRoundFactCodec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class TestRoundMembers {
    private static final MinimalRoundFactCodec CODEC = new MinimalRoundFactCodec();
    private TestRoundMembers() { }

    static String loss(int betType) { return CODEC.encode(paidFact(betType, false)); }
    static String win(int betType) { return CODEC.encode(paidFact(betType, true)); }
    static String naturalFree() { return CODEC.encode(freeFact(false, false)); }
    static String naturalFreeWithPaidWin() { return CODEC.encode(freeFact(false, true)); }
    static String featureBuy() { return CODEC.encode(freeFact(true, false)); }
    static String incrementLoss(int slot, boolean withScatter) {
        return incrementLoss(1, slot, withScatter);
    }
    static String incrementLoss(int betType, int slot, boolean withScatter) {
        List<int[]> boards = new ArrayList<>();
        for (int axis = 0; axis < betType; axis++) {
            int[] board = rotatedLossBoard(axis * 2);
            if (withScatter && axis == 0) board[0] = GameRuleCore.SCATTER;
            boards.add(board);
        }
        int[] delta = new int[5]; delta[slot] = 1;
        return CODEC.encode(new GameRuleCore.CompleteRoundFact(GameRuleCore.EntryKind.PAID_INITIAL, betType,
                GameRuleCore.countSymbol(boards, GameRuleCore.SCATTER), delta,
                GameRuleCore.CoinTransition.INCREMENT_NONDECREASING, 0,
                List.of(new GameRuleCore.Step(boards))));
    }
    static String coinRewardFive() {
        List<int[]> boards = new ArrayList<>();
        for (int axis = 0; axis < 3; axis++) boards.add(rotatedLossBoard(axis * 2));
        return CODEC.encode(new GameRuleCore.CompleteRoundFact(GameRuleCore.EntryKind.PAID_INITIAL, 3,
                GameRuleCore.countSymbol(boards, GameRuleCore.SCATTER), new int[5],
                GameRuleCore.CoinTransition.FULL_TRIGGER, 5, List.of(new GameRuleCore.Step(boards))));
    }

    static GameRuleCore.CompleteRoundFact paidFact(int betType, boolean winning) {
        List<int[]> boards = new ArrayList<>();
        for (int axis = 0; axis < betType; axis++) boards.add(winning && axis == 0 ? winBoard() : rotatedLossBoard(axis * 2));
        return new GameRuleCore.CompleteRoundFact(GameRuleCore.EntryKind.PAID_INITIAL, betType,
                GameRuleCore.countSymbol(boards, GameRuleCore.SCATTER), new int[5],
                GameRuleCore.CoinTransition.UNCHANGED, 0, List.of(new GameRuleCore.Step(boards)));
    }

    static GameRuleCore.CompleteRoundFact freeFact(boolean buy, boolean paidWin) {
        List<GameRuleCore.Step> steps = new ArrayList<>();
        // 独立边界夹具可令自然 Free 起点带中奖，覆盖触发步 total_win 进入 frees.twa 的原厂行为。
        steps.add(new GameRuleCore.Step(buy ? buyPage() : List.of(paidWin ? winBoard() : lossBoard())));
        for (int i = 0; i < 5; i++) steps.add(new GameRuleCore.Step(freePage()));
        return new GameRuleCore.CompleteRoundFact(buy ? GameRuleCore.EntryKind.FEATURE_BUY_INITIAL : GameRuleCore.EntryKind.PAID_INITIAL,
                1, GameRuleCore.countSymbol(steps.get(0).boards(), GameRuleCore.SCATTER), new int[5],
                GameRuleCore.CoinTransition.UNCHANGED, 0, steps);
    }

    static int multiplier(String member) { return CODEC.verify(member).multiplier(); }

    private static int[] lossBoard() { return new int[]{4,9,7,7,7,4,3,7,9,6,5,6,7,8,8}; }
    private static int[] rotatedLossBoard(int shift) {
        int[] board = lossBoard();
        for (int i = 0; i < board.length; i++) board[i] = ((board[i] - 1 + shift) % 9) + 1;
        return board;
    }
    private static int[] winBoard() { return new int[]{1,1,1,4,5,6,7,8,9,2,3,4,5,6,7}; }
    private static List<int[]> freePage() {
        return List.of(freeBoard(9), freeBoard(8), freeBoard(7));
    }
    private static int[] freeBoard(int big) {
        int[] board = new int[15];
        int[] center = {1,2,3,6,7,8,11,12,13};
        for (int position : center) board[position] = big;
        int[] outer = {0,4,5,9,10,14};
        // row=1 的左右外框与中心大符号相同，形成一条已知25线中奖，确保测试 special 倍率为正。
        int[] materials = {1,2,big,big,3,4};
        for (int i = 0; i < outer.length; i++) board[outer[i]] = materials[i];
        return board;
    }
    private static List<int[]> buyPage() {
        int[][] nonScatter = {{3,3,4,5,6,6},{2,7,8,8,9},{2,4,4,4}};
        List<int[]> result = new ArrayList<>();
        for (int axis = 0; axis < 3; axis++) {
            int[] board = new int[15]; Arrays.fill(board, GameRuleCore.SCATTER);
            int cursor = 0;
            for (int position = 0; position < 15; position++) {
                if (!contains(BoardCaps.BUY_SCATTER_POSITIONS[axis], position)) board[position] = nonScatter[axis][cursor++];
            }
            result.add(board);
        }
        return result;
    }
    private static boolean contains(int[] values, int target) { for (int value : values) if (value == target) return true; return false; }
}
