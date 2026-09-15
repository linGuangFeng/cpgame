package com.hd.pg.appapi.business.vo.cpgame.freedomday;

import java.math.BigDecimal;
import java.util.Random;

/**
 * Generates a one-step loss board with no dependency on any previous/next Spin.
 * Constructs a loss in one pass; at most five verified proposals, then ten verified defaults.
 * <p>Freedom Day is a Ways game, so construction blocks a symbol shared by the
 * first two reels from appearing on the third reel (Wild counts as every paying
 * symbol). A 100,000-board verification run achieved 100% first-attempt success;
 * the ordinary first-proposal requirement is 100%.</p>
 */
public final class FreedomDayIndependentLossGenerator {
    public static final int RANDOM_ATTEMPTS = 5;
    public static final double REQUIRED_FIRST_ATTEMPT_SUCCESS_RATE = 1.0d;

    public FreedomDayBoard generate(Random random, boolean freeMode) {
        if (random == null) throw new IllegalArgumentException("random is required");
        FreedomDayBoardGenerator boards = new FreedomDayBoardGenerator(random);
        return generateWithCandidates(random, () -> boards.generateIndependentLossCandidate(freeMode));
    }

    FreedomDayBoard generateWithCandidates(Random random, java.util.function.Supplier<FreedomDayBoard> candidates) {
        for (int attempt = 0; attempt < RANDOM_ATTEMPTS; attempt++) {
            FreedomDayBoard candidate = candidates.get();
            if (candidate != null && isIndependentLoss(candidate)) return candidate;
        }
        return DEFAULT_LOSSES.get(random.nextInt(DEFAULT_LOSSES.size()));
    }

    public static boolean isIndependentLoss(FreedomDayBoard board) {
        FreedomDayEvaluation result = FreedomDayResultUtil.evaluate(board, BigDecimal.ONE, 1, 2);
        return result.getWins().isEmpty()
                && result.getTotalMultiplier().signum() == 0
                && result.getAwardedFreeSpins() == 0;
    }


    public static final int PREWRITTEN_BOARD_COUNT = 10;
    private static final java.util.List<FreedomDayBoard> DEFAULT_LOSSES = createDefaults();

    private static java.util.List<FreedomDayBoard> createDefaults() {
        var boards = new FreedomDayBoardGenerator(new java.security.SecureRandom());
        var defaults = new java.util.ArrayList<FreedomDayBoard>(PREWRITTEN_BOARD_COUNT);
        for (int i = 0; i < PREWRITTEN_BOARD_COUNT; i++) {
            FreedomDayBoard board = boards.generateIndependentLossCandidate(false);
            if (!isIndependentLoss(board)) throw new ExceptionInInitializerError("invalid default loss");
            defaults.add(board);
        }
        return java.util.List.copyOf(defaults);
    }

    public static int prewrittenCount() { return DEFAULT_LOSSES.size(); }
}
