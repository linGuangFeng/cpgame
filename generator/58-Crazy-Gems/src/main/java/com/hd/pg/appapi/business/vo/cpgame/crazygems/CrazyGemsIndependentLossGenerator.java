package com.hd.pg.appapi.business.vo.cpgame.crazygems;

import java.util.Random;

/**
 * Independent 0x constructor for the five fixed paylines. Wild substitutes.
 * A line is blocked when two distinct natural symbols sit on it.
 */
public final class CrazyGemsIndependentLossGenerator {
    public static final int RANDOM_ATTEMPTS = 5;
    public static final double REQUIRED_FIRST_ATTEMPT_SUCCESS_RATE = 1.0d;

    public CrazyGemsBoard generate(Random random) {
        if (random == null) throw new IllegalArgumentException("random is required");
        CrazyGemsBoardGenerator boards = new CrazyGemsBoardGenerator(random);
        return generateWithCandidates(random, () -> boards.generateIndependentLossCandidate());
    }

    CrazyGemsBoard generateWithCandidates(Random random, java.util.function.Supplier<CrazyGemsBoard> candidates) {
        for (int attempt = 0; attempt < RANDOM_ATTEMPTS; attempt++) {
            CrazyGemsBoard candidate = candidates.get();
            if (candidate != null && isIndependentLoss(candidate)) return candidate;
        }
        return DEFAULT_LOSSES.get(random.nextInt(DEFAULT_LOSSES.size()));
    }

    public static boolean isIndependentLoss(CrazyGemsBoard board) {
        CrazyGemsEvaluation evaluation = CrazyGemsResultUtil.evaluate(board);
        return evaluation.loss() && evaluation.multiplierDeci() == 0;
    }

    public static final int PREWRITTEN_BOARD_COUNT = 10;
    private static final java.util.List<CrazyGemsBoard> DEFAULT_LOSSES = createDefaults();

    private static java.util.List<CrazyGemsBoard> createDefaults() {
        var boards = new CrazyGemsBoardGenerator(new java.security.SecureRandom());
        var defaults = new java.util.ArrayList<CrazyGemsBoard>(PREWRITTEN_BOARD_COUNT);
        for (int i = 0; i < PREWRITTEN_BOARD_COUNT; i++) {
            CrazyGemsBoard board = boards.generateIndependentLossCandidate();
            if (!isIndependentLoss(board)) throw new ExceptionInInitializerError("invalid default loss");
            defaults.add(board);
        }
        return java.util.List.copyOf(defaults);
    }

    public static int prewrittenCount() { return DEFAULT_LOSSES.size(); }
}
