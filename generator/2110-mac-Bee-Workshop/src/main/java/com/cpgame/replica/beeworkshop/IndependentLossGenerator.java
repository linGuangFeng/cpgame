package com.cpgame.replica.beeworkshop;

import java.util.List;
import java.util.Random;

/**
 * Payline independent 0-unit constructor. Blocks the third reel of every 20-line
 * left-to-right match and keeps Scatter below trigger. Used to fill BetLog 0.
 */
public final class IndependentLossGenerator {
    public static final int RANDOM_ATTEMPTS = 100;
    public static final double REQUIRED_FIRST_ATTEMPT_SUCCESS_RATE = 0.90d;
    private final GameRuleCore rules = new GameRuleCore();
    private final ResultUtil util = new ResultUtil(rules);
    private final EmpiricalDealModel model = EmpiricalDealModel.shared();
    private final BeeWorkshopBoardGenerator weighted;

    public IndependentLossGenerator() { this(null); }

    public IndependentLossGenerator(int[] ordinaryWeights) {
        this.weighted = ordinaryWeights == null ? null : new BeeWorkshopBoardGenerator(ordinaryWeights);
    }

    public GameRuleCore.CompleteRound generate(Random random) {
        if (random == null) throw new IllegalArgumentException("random is required");
        for (int attempt = 0; attempt < RANDOM_ATTEMPTS; attempt++) {
            int[] board = weighted != null ? weighted.generate(random) : model.board("ORDINARY", 0, true, random);
            if (board == null) continue;
            board = forceLoss(board);
            if (!isIndependentLoss(board)) continue;
            try {
                var step = new GameRuleCore.Step(board, List.of());
                rules.validateDealtStep("ORDINARY_LOSS", step);
                var round = new GameRuleCore.CompleteRound(GameRuleCore.RoundKind.ORDINARY_LOSS, List.of(step));
                rules.validate(round);
                if (util.integerMultiplier(round) != 0) continue;
                return round;
            } catch (IllegalArgumentException rejected) {
                /* empirical cap or sticky/wild bound; retry the whole candidate */
            }
        }
        throw new IllegalStateException("cannot construct an independently verified loss after " + RANDOM_ATTEMPTS + " attempts");
    }

    public boolean isIndependentLoss(int[] board) {
        return util.independentPayoutUnits(board) == 0
                && rules.payoutUnits(board) == 0
                && rules.scatterCount(board) < 3;
    }

    int[] forceLoss(int[] board) {
        int[] out = board.clone();
        int scatters = 0;
        for (int i = 0; i < out.length; i++) {
            if (out[i] == GameRuleCore.WILD && (i / 3 == 0 || i / 3 == 4)) out[i] = 7;
            if (out[i] == GameRuleCore.SCATTER) {
                scatters++;
                if (scatters > 2) out[i] = 7;
            }
        }
        if (isIndependentLoss(out)) return out;
        for (int symbol = 7; symbol >= 1; symbol--) {
            int[] candidate = out.clone();
            for (int row = 0; row < 3; row++) candidate[2 * 3 + row] = symbol;
            if (isIndependentLoss(candidate)) return candidate;
        }
        for (int symbol = 7; symbol >= 1; symbol--) {
            int[] candidate = out.clone();
            for (int row = 0; row < 3; row++) {
                candidate[2 * 3 + row] = symbol;
                candidate[1 * 3 + row] = symbol == 7 ? 6 : 7;
            }
            if (isIndependentLoss(candidate)) return candidate;
        }
        return out;
    }
}
