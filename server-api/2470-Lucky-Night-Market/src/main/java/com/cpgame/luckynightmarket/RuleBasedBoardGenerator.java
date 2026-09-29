package com.cpgame.luckynightmarket;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * 按单牌权重生成全新事实。采集牌面、整列、中奖组合和倍率三元组都不会成为运行时候选。
 */
final class RuleBasedBoardGenerator {
    private static final int[] ALL_SYMBOLS = {0, 1, 2, 3, 4, 5, 6};
    static final int[] REEL_MULTIPLIERS = {1, 2, 3, 5, 10, 15};
    // 3705 个采集步骤中非零倍率的边际次数，只用于随机权重，不限制倍率组合。
    private static final double[] REEL_MULTIPLIER_WEIGHTS = {3155, 3266, 2178, 1696, 323, 424};
    static final int[] WHEEL_PRIZES = {1, 3, 5, 8, 10, 15, 20, 30, 50, 100, 200, 1000};

    private final SecureRandom random;
    private final SymbolWeightSchedule symbolWeights;

    RuleBasedBoardGenerator(SecureRandom random, SymbolWeightSchedule symbolWeights) {
        this.random = random;
        this.symbolWeights = symbolWeights;
    }

    RoundFact.Step ordinary() {
        return new RoundFact.Step(randomBoard(), multipliers(), 0);
    }

    RoundFact.Step featureStep() {
        return new RoundFact.Step(randomBoard(), multipliers(), 0);
    }

    RoundFact.Step wheel() {
        return new RoundFact.Step(
                randomBoard(),
                List.of(reelMultiplier(), 0, reelMultiplier()),
                WHEEL_PRIZES[random.nextInt(WHEEL_PRIZES.length)]);
    }

    private List<Integer> randomBoard() {
        List<Integer> board = new ArrayList<>(9);
        for (int position = 0; position < 9; position++) {
            board.add(symbolWeights.pick(random, ALL_SYMBOLS));
        }
        return board;
    }

    private List<Integer> multipliers() {
        return List.of(reelMultiplier(), reelMultiplier(), reelMultiplier());
    }

    private int reelMultiplier() {
        double total = 0.0d;
        for (double weight : REEL_MULTIPLIER_WEIGHTS) total += weight;
        double draw = random.nextDouble() * total;
        for (int i = 0; i < REEL_MULTIPLIERS.length; i++) {
            draw -= REEL_MULTIPLIER_WEIGHTS[i];
            if (draw < 0.0d) return REEL_MULTIPLIERS[i];
        }
        return REEL_MULTIPLIERS[REEL_MULTIPLIERS.length - 1];
    }

    static int winningStepCount(RoundFact round) {
        int count = 0;
        for (RoundFact.Step step : round.steps()) {
            if (GameRuleCore.evaluate(step, round.feature()).units() > 0) count++;
        }
        return count;
    }
}
