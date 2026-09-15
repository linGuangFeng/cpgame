package com.hd.pg.appapi.business.model.cpgame.hotpot;

import java.util.Random;

/**
 * One-step ordinary loss: no 8-of-a-kind and no Scatter Free Spins trigger.
 * Construction is constrained, then ResultUtil independently accepts the board.
 */
public final class HotpotIndependentLossGenerator {
    public static final int RANDOM_ATTEMPTS = 5;
    public static final double REQUIRED_FIRST_ATTEMPT_SUCCESS_RATE = 1.0d;

    public HotpotBoard generate(Random random) {
        return generate(random, HotpotSpinMode.PAID);
    }

    public HotpotBoard generate(Random random, HotpotSpinMode mode) {
        if (random == null || mode == null) throw new IllegalArgumentException("random/mode required");
        HotpotBoardGenerator boards = new HotpotBoardGenerator(random);
        return generateWithCandidates(random, () -> boards.generateIndependentLossCandidate(mode), mode);
    }

    HotpotBoard generateWithCandidates(Random random, java.util.function.Supplier<HotpotBoard> candidates) {
        return generateWithCandidates(random, candidates, HotpotSpinMode.PAID);
    }

    private HotpotBoard generateWithCandidates(Random random, java.util.function.Supplier<HotpotBoard> candidates,
                                               HotpotSpinMode mode) {
        for (int attempt = 0; attempt < RANDOM_ATTEMPTS; attempt++) {
            HotpotBoard candidate = candidates.get();
            if (candidate != null && isIndependentLoss(candidate, mode)) return candidate;
        }
        var defaults = mode == HotpotSpinMode.FREE ? FREE_DEFAULT_LOSSES : DEFAULT_LOSSES;
        return defaults.get(random.nextInt(defaults.size()));
    }

    public static boolean isIndependentLoss(HotpotBoard board) {
        return isIndependentLoss(board, HotpotSpinMode.PAID);
    }

    public static boolean isIndependentLoss(HotpotBoard board, HotpotSpinMode mode) {
        java.util.Objects.requireNonNull(mode, "mode");
        HotpotEvaluation evaluation = HotpotResultUtil.evaluate(board);
        for (int col = 0; col < HotpotBoard.COLUMNS; col++) {
            int scatters = 0;
            for (int row = 0; row < HotpotBoard.ROWS; row++) {
                if (board.getProp()[col * HotpotBoard.ROWS + row] == HotpotResultUtil.SCATTER) scatters++;
            }
            if (scatters > HotpotResultUtil.MAX_SCATTER_PER_COLUMN) return false;
        }
        return evaluation.getPageKind() == HotpotPageKind.TERMINAL_NO_WIN
                && evaluation.getOddSum() == 0
                && HotpotResultUtil.awardedFreeSpins(evaluation.getScatterCount(), mode) == 0;
    }

    public static final int PREWRITTEN_BOARD_COUNT = 10;
    private static final java.util.List<HotpotBoard> DEFAULT_LOSSES = createDefaults(HotpotSpinMode.PAID);
    private static final java.util.List<HotpotBoard> FREE_DEFAULT_LOSSES = createDefaults(HotpotSpinMode.FREE);

    private static java.util.List<HotpotBoard> createDefaults(HotpotSpinMode mode) {
        var boards = new HotpotBoardGenerator(new java.security.SecureRandom());
        var defaults = new java.util.ArrayList<HotpotBoard>(PREWRITTEN_BOARD_COUNT);
        for (int i = 0; i < PREWRITTEN_BOARD_COUNT; i++) {
            HotpotBoard board = boards.generateIndependentLossCandidate(mode);
            if (!isIndependentLoss(board, mode)) throw new ExceptionInInitializerError("invalid default loss");
            defaults.add(board);
        }
        return java.util.List.copyOf(defaults);
    }

    public static int prewrittenCount() { return DEFAULT_LOSSES.size(); }
}
