package com.cpgame.sambasensation.core;

import java.util.ArrayList;
import java.util.List;

/** 独立从可见事实反推线奖、完整局模式和整数倍率；不信任 Factory 或 Codec 的派生值。 */
public final class ResultUtil {
    private ResultUtil() { }

    public record LineWin(int axis, int line, int symbol, int count, int multiplier) { }
    /** Controller 投影单次响应时使用；判奖仍只发生在这一份共享 ResultUtil 中。 */
    public record StepEvaluation(int multiplier, List<LineWin> wins) { }
    public record Evaluation(GameRuleCore.RoundClass roundClass, int multiplier, List<LineWin> wins,
                             int winningSteps, int maxConsecutiveWinningSteps) { }

    public static Evaluation evaluate(GameRuleCore.CompleteRoundFact fact) {
        GameRuleCore.validateStructure(fact);
        List<LineWin> wins = new ArrayList<>();
        int total = 0;
        int winningSteps = 0;
        int consecutive = 0;
        int maxConsecutive = 0;
        for (int stepIndex = 0; stepIndex < fact.steps().size(); stepIndex++) {
            StepEvaluation step = evaluateDelivery(fact, stepIndex);
            total += step.multiplier();
            wins.addAll(step.wins());
            if (step.multiplier() > 0) {
                winningSteps++;
                consecutive++;
                maxConsecutive = Math.max(maxConsecutive, consecutive);
            }
            if (step.multiplier() == 0) consecutive = 0;
        }
        GameRuleCore.RoundClass kind = classify(fact, total);
        return new Evaluation(kind, total, List.copyOf(wins), winningSteps, maxConsecutive);
    }

    /**
     * 从完整 Redis member 的指定 Step 独立复算该次响应。
     * 金币满槽奖励只在唯一的付费响应结算；免费 Step 不会再次加入该奖励。
     */
    public static StepEvaluation evaluateDelivery(GameRuleCore.CompleteRoundFact fact, int stepIndex) {
        GameRuleCore.validateStructure(fact);
        if (stepIndex < 0 || stepIndex >= fact.steps().size()) {
            throw new IllegalArgumentException("stepIndex outside complete Round");
        }
        List<LineWin> wins = new ArrayList<>();
        int total = 0;
        List<int[]> boards = fact.steps().get(stepIndex).boards();
        // 原厂购买/Mary触发页(type=3)只启动同一Free状态机且该页必定0奖；后续五页仍按25线正常判奖。
        boolean featureBuyTrigger = stepIndex == 0
                && fact.entryKind() == GameRuleCore.EntryKind.FEATURE_BUY_INITIAL;
        if (!featureBuyTrigger) {
            for (int axis = 0; axis < boards.size(); axis++) {
                total += evaluateBoard(boards.get(axis), axis + 1, wins);
            }
        }
        if (stepIndex == 0 && fact.coinTransition() == GameRuleCore.CoinTransition.FULL_TRIGGER) {
            total += fact.coinRewardCount() * 25;
        }
        return new StepEvaluation(total, List.copyOf(wins));
    }

    private static GameRuleCore.RoundClass classify(GameRuleCore.CompleteRoundFact fact, int multiplier) {
        if (fact.coinTransition() == GameRuleCore.CoinTransition.FULL_TRIGGER) return GameRuleCore.RoundClass.COIN_COLLECTION_REWARD;
        if (fact.steps().size() == 6) return GameRuleCore.RoundClass.FREE_SPINS_SPECIAL;
        if (multiplier == 0) return GameRuleCore.RoundClass.ORDINARY_LOSS;
        if (multiplier > 0) return GameRuleCore.RoundClass.ORDINARY_WIN;
        throw new IllegalStateException("round-state partition is incomplete");
    }

    private static int evaluateBoard(int[] board, int axis, List<LineWin> output) {
        int boardTotal = 0;
        for (int lineIndex = 0; lineIndex < GameRuleCore.PAYLINES.length; lineIndex++) {
            int symbol = targetSymbol(board, GameRuleCore.PAYLINES[lineIndex]);
            int count = symbol == 0 ? 0 : consecutive(board, GameRuleCore.PAYLINES[lineIndex], symbol);
            int multiplier = GameRuleCore.payMultiplier(symbol, count);
            if (multiplier > 0) {
                output.add(new LineWin(axis, lineIndex + 1, symbol, count, multiplier));
                boardTotal += multiplier;
            }
        }
        return boardTotal;
    }

    private static int targetSymbol(int[] board, int[] rows) {
        for (int column = 0; column < GameRuleCore.COLUMNS; column++) {
            int symbol = board[rows[column] * GameRuleCore.COLUMNS + column];
            if (symbol == GameRuleCore.SCATTER) return 0;
            if (symbol != GameRuleCore.WILD) return symbol;
        }
        return 1;
    }

    private static int consecutive(int[] board, int[] rows, int target) {
        int count = 0;
        for (int column = 0; column < GameRuleCore.COLUMNS; column++) {
            int symbol = board[rows[column] * GameRuleCore.COLUMNS + column];
            if (symbol == target || symbol == GameRuleCore.WILD) count++;
            if (symbol != target && symbol != GameRuleCore.WILD) return count;
        }
        return count;
    }
}
