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
    /** Captured 1809 zero-win pages currently contain at most three visible x2 balls. */
    public static final int MAX_MARKER_BALLS = 3;

    public FreedomDayBoard generate(Random random, boolean freeMode) {
        if (random == null) throw new IllegalArgumentException("random is required");
        FreedomDayBoardGenerator boards = new FreedomDayBoardGenerator(random);
        return generateWithCandidates(random, () -> boards.generateIndependentLossCandidate(freeMode));
    }

    /** Materializes an independent-loss cache marker with no multiplier increment. */
    public FreedomDayBoard generateMarkerLoss(Random random, boolean freeMode) {
        return generateMarkerLoss(random, freeMode, 0);
    }

    public FreedomDayBoard generateMarkerLoss(Random random, boolean freeMode, int ballCount) {
        if (random == null) throw new IllegalArgumentException("random is required");
        if (ballCount < 0 || ballCount > MAX_MARKER_BALLS) {
            throw new IllegalArgumentException("marker ball count must be 0.." + MAX_MARKER_BALLS);
        }
        int[] normalWeights = FreedomDayBoardGenerator.defaultNormalWeights();
        int[] freeWeights = FreedomDayBoardGenerator.defaultFreeWeights();
        normalWeights[FreedomDayResultUtil.BALL - 1] = 0;
        freeWeights[FreedomDayResultUtil.BALL - 1] = 0;
        FreedomDayBoardGenerator boards = new FreedomDayBoardGenerator(random, normalWeights, freeWeights);
        FreedomDayBoard base = null;
        for (int attempt = 0; attempt < RANDOM_ATTEMPTS; attempt++) {
            FreedomDayBoard candidate = boards.generateIndependentLossCandidate(freeMode);
            if (isMarkerLoss(candidate)) {
                base = candidate;
                break;
            }
        }
        if (base == null) base = DEFAULT_MARKER_LOSSES.get(random.nextInt(DEFAULT_MARKER_LOSSES.size()));
        if (ballCount == 0) return base;

        int[] prop = base.getProp();
        int[] rows = {0, 1, 2, 3, 4};
        for (int i = rows.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int value = rows[i]; rows[i] = rows[j]; rows[j] = value;
        }
        int lastReel = (FreedomDayBoard.REEL_COUNT - 1) * FreedomDayBoard.ROW_COUNT;
        for (int i = 0; i < ballCount; i++) prop[lastReel + rows[i]] = FreedomDayResultUtil.BALL;
        FreedomDayBoard result = new FreedomDayBoard(prop, base.getTrl(), base.getGrids(),
                base.getGoldFrames(), base.getSilverFrames());
        if (!isIndependentLoss(result)
                || FreedomDayResultUtil.countVisibleSymbol(result, FreedomDayResultUtil.BALL) != ballCount) {
            throw new IllegalStateException("cannot construct exact marker loss");
        }
        return result;
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

    public static boolean isMarkerLoss(FreedomDayBoard board) {
        return isIndependentLoss(board)
                && FreedomDayResultUtil.countVisibleSymbol(board, FreedomDayResultUtil.BALL) == 0;
    }

    public static boolean isCompactableLoss(FreedomDayBoard board) {
        int balls = FreedomDayResultUtil.countVisibleSymbol(board, FreedomDayResultUtil.BALL);
        return balls >= 0 && balls <= MAX_MARKER_BALLS && isIndependentLoss(board);
    }


    public static final int PREWRITTEN_BOARD_COUNT = 10;
    private static final java.util.List<FreedomDayBoard> DEFAULT_LOSSES = createDefaults();
    private static final java.util.List<FreedomDayBoard> DEFAULT_MARKER_LOSSES = createMarkerDefaults();

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

    private static java.util.List<FreedomDayBoard> createMarkerDefaults() {
        int[] normalWeights = FreedomDayBoardGenerator.defaultNormalWeights();
        int[] freeWeights = FreedomDayBoardGenerator.defaultFreeWeights();
        normalWeights[FreedomDayResultUtil.BALL - 1] = 0;
        freeWeights[FreedomDayResultUtil.BALL - 1] = 0;
        var boards = new FreedomDayBoardGenerator(new java.security.SecureRandom(), normalWeights, freeWeights);
        var defaults = new java.util.ArrayList<FreedomDayBoard>(PREWRITTEN_BOARD_COUNT);
        for (int i = 0; i < PREWRITTEN_BOARD_COUNT; i++) {
            FreedomDayBoard board = boards.generateIndependentLossCandidate(false);
            if (!isMarkerLoss(board)) throw new ExceptionInInitializerError("invalid default marker loss");
            defaults.add(board);
        }
        return java.util.List.copyOf(defaults);
    }

    public static int prewrittenCount() { return DEFAULT_LOSSES.size(); }
}
