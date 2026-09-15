package com.cpgame.g1910.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** gid 1910 的唯一可执行规则核心；Loader 与 Controller v3 共同编译此源码。 */
public final class GameRuleCore {
    public static final int GAME_ID = 1910;
    public static final String GAME_NAME = "Churrasco";
    public static final String RULES_VERSION = "1910-formal-provider-v40-1398-complete";
    public static final String RULES_HASH = "4b8bc4cc855608dffb6069f45cae4ff98f4f07391b7907689d1c303366bf5915";
    public static final int WILD = 12;
    public static final int SCATTER = 13;
    public static final int RETRIGGER_FREE_STEPS = 8;

    // 视觉行号从上到下为 0..2；provider 的 prop 为逐列、每列从下到上。
    public static final int[][] PAYLINES = {
        {1,1,1,1,1},{2,2,2,2,2},{0,0,0,0,0},{2,1,0,1,2},{0,1,2,1,0},
        {1,2,2,2,1},{1,0,0,0,1},{2,2,1,0,0},{0,0,1,2,2},{1,0,1,2,1},
        {1,2,1,0,1},{2,1,1,1,2},{0,1,1,1,0},{2,1,2,1,2},{0,1,0,1,0},
        {1,1,2,1,1},{1,1,0,1,1},{2,2,0,2,2},{0,0,2,0,0},{2,0,0,0,2},
        {0,2,2,2,0},{1,0,2,0,1},{1,2,0,2,1},{2,0,2,0,2},{0,2,0,2,0}
    };

    private static final Map<Integer, int[]> PAYTABLE = Map.ofEntries(
        Map.entry(1, new int[]{0,10,50,250,750}), Map.entry(2, new int[]{0,5,40,200,500}),
        Map.entry(3, new int[]{0,0,30,150,400}), Map.entry(4, new int[]{0,0,25,100,250}),
        Map.entry(5, new int[]{0,0,25,100,250}), Map.entry(6, new int[]{0,0,10,50,150}),
        Map.entry(7, new int[]{0,0,10,50,150}), Map.entry(8, new int[]{0,0,5,25,100}),
        Map.entry(9, new int[]{0,0,5,25,100}), Map.entry(10,new int[]{0,0,5,25,100}),
        Map.entry(11,new int[]{0,0,5,25,100}), Map.entry(12,new int[]{0,25,150,1000,2500})
    );

    public enum Mode { ORDINARY, SMALL_GAME_1, FREE_REWARD }
    public enum Outcome { LOSS, WIN, SMALL_GAME_1, FREE_REWARD }

    public record Step(int[] symbols) {
        public Step {
            if (symbols == null || symbols.length != 15) throw new IllegalArgumentException("board must contain 15 symbols");
            symbols = symbols.clone();
            for (int symbol : symbols) if (symbol < 1 || symbol > 13) throw new IllegalArgumentException("invalid symbol " + symbol);
            for (int column = 0; column < 5; column++) {
                int scatters = 0;
                for (int row = 0; row < 3; row++) if (symbols[column * 3 + row] == SCATTER) scatters++;
                if (scatters > 1) throw new IllegalArgumentException("multiple Scatter symbols in column " + column);
            }
        }
        @Override public int[] symbols() { return symbols.clone(); }
    }

    public record CompleteRound(Mode mode, Step paid, List<Step> freeSteps, int freeTimes, int freeMultiplier) {
        public CompleteRound {
            if (mode == null || paid == null || freeSteps == null) throw new IllegalArgumentException("round fields are required");
            freeSteps = List.copyOf(freeSteps);
        }
    }

    public record Win(int line, int multiplier, int count, int odd, int symbol, int[] positions, int units) {
        @Override public int[] positions() { return positions.clone(); }
    }
    public record StepEvaluation(List<Win> wins, int units, int scatterCount) { }
    public record Evaluation(Outcome outcome, StepEvaluation paid, List<StepEvaluation> freeSteps, int totalUnits) { }

    private GameRuleCore() { }
    public static boolean runtimeIndependentLossSupported() { return true; }

    public static Evaluation evaluate(CompleteRound round) {
        StepEvaluation paid = evaluateStep(round.paid(), 1);
        int paidScatters = paid.scatterCount();
        switch (round.mode()) {
            case ORDINARY -> {
                if (!round.freeSteps().isEmpty() || round.freeTimes() != 0 || round.freeMultiplier() != 1 || paidScatters >= 3)
                    throw new IllegalArgumentException("ordinary state mismatch");
            }
            case SMALL_GAME_1 -> {
                if (!round.freeSteps().isEmpty() || round.freeTimes() != 0 || round.freeMultiplier() != 1 || paidScatters >= 3 || paid.units() != 0)
                    throw new IllegalArgumentException("small_game_type=1 is an observed one-step zero-win state");
            }
            case FREE_REWARD -> {
                if (paidScatters < 3 || paidScatters > 4 || paid.units() != 0)
                    throw new IllegalArgumentException("free reward requires a zero-win 3/4 Scatter paid trigger");
                if (!allowedFreeTimes(paidScatters, round.freeTimes()) || !List.of(2,5,8).contains(round.freeMultiplier()))
                    throw new IllegalArgumentException("invalid observed selector result");
                int expected=round.freeTimes();
                for(Step step:round.freeSteps())if(scatterCount(step.symbols())>=3)expected+=RETRIGGER_FREE_STEPS;
                if (round.freeSteps().size() != expected)
                    throw new IllegalArgumentException("complete free chain must include every retriggered step");
            }
        }
        List<StepEvaluation> free = new ArrayList<>();
        int total = paid.units();
        for (Step step : round.freeSteps()) {
            StepEvaluation evaluated = evaluateStep(step, round.freeMultiplier());
            free.add(evaluated);
            total += evaluated.units();
        }
        Outcome outcome = switch (round.mode()) {
            case SMALL_GAME_1 -> Outcome.SMALL_GAME_1;
            case FREE_REWARD -> Outcome.FREE_REWARD;
            case ORDINARY -> total == 0 ? Outcome.LOSS : Outcome.WIN;
        };
        return new Evaluation(outcome, paid, List.copyOf(free), total);
    }

    public static StepEvaluation evaluateStep(Step step, int freeMultiplier) {
        int[] board = step.symbols();
        List<Win> wins = new ArrayList<>();
        int total = 0;
        for (int lineIndex = 0; lineIndex < PAYLINES.length; lineIndex++) {
            int[] rows = PAYLINES[lineIndex];
            int first = symbolAt(board, 0, rows[0]);
            int target = first == WILD ? -1 : first;
            int count = 0;
            boolean hasWild = false;
            int[] positions = new int[5];
            for (int column = 0; column < 5; column++) {
                int symbol = symbolAt(board, column, rows[column]);
                if (symbol == SCATTER) break;
                if (target < 0 && symbol != WILD) target = symbol;
                if (target < 0 || symbol == target || symbol == WILD) {
                    hasWild |= symbol == WILD;
                    positions[count++] = column * 3 + (2 - rows[column]);
                } else break;
            }
            if (target < 0) target = WILD;
            int[] odds = PAYTABLE.get(target);
            int odd = count >= 2 && odds != null ? odds[count - 1] : 0;
            if (odd > 0) {
                int wildMultiplier = hasWild ? 2 : 1;
                int units = odd * wildMultiplier * freeMultiplier;
                wins.add(new Win(lineIndex + 1, wildMultiplier, count, odd, target,
                    java.util.Arrays.copyOf(positions, count), units));
                total += units;
            }
        }
        return new StepEvaluation(List.copyOf(wins), total, scatterCount(board));
    }

    private static int symbolAt(int[] board, int column, int visualRow) { return board[column * 3 + (2 - visualRow)]; }
    public static int scatterCount(int[] board) { int n=0; for (int value : board) if (value == SCATTER) n++; return n; }
    public static boolean allowedFreeTimes(int scatterCount, int times) {
        return scatterCount == 3 ? List.of(8,12,20).contains(times) : scatterCount == 4 && List.of(12,16,24).contains(times);
    }
    public static BigDecimal paidBet(BigDecimal bet, int level) { return bet.multiply(BigDecimal.valueOf(level * 25L)).setScale(2, RoundingMode.HALF_UP); }
    public static BigDecimal award(BigDecimal bet, int level, int units) { return bet.multiply(BigDecimal.valueOf((long) level * units)).setScale(2, RoundingMode.HALF_UP); }
    public static BigDecimal displayedOdds(BigDecimal award, BigDecimal paidBet) {
        return paidBet.signum() == 0 ? BigDecimal.ZERO : award.divide(paidBet, 4, RoundingMode.HALF_UP).stripTrailingZeros();
    }
}
