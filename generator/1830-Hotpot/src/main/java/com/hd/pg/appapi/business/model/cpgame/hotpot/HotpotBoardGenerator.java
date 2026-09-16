package com.hd.pg.appapi.business.model.cpgame.hotpot;

import java.security.SecureRandom;
import java.util.Random;

/**
 * Weighted board construction only. No pay evaluation lives here.
 * Scene weights are empirical cell counts; start weights are never used as cascade fill.
 */
public final class HotpotBoardGenerator {
    public static final int SYMBOL_COUNT = 23;

    /**
     * PAID_START_PAGE: 1391 first type=1 boards, 50076 cells.
     * captures/1830-Hotpot/round-bodies step-001 first props[0].
     */
    public static final int[] DEFAULT_PAID_START_WEIGHTS = {
            5351, 5179, 4917, 4903, 4788, 4833, 4790, 4885, 4692, 4752,
            557, 89, 111, 105, 124, 0, 0, 0, 0, 0, 0, 0, 0
    };

    /**
     * CASCADE_OR_LATER_PAGE: 1583 later boards, 56988 cells (survivors plus fills).
     * Not claimed as origin-RNG fill-only weights.
     */
    public static final int[] DEFAULT_CASCADE_WEIGHTS = {
            5432, 5499, 5735, 5505, 5432, 5386, 5418, 5247, 5510, 5295,
            553, 226, 394, 360, 701, 50, 51, 40, 45, 60, 32, 12, 5
    };

    /**
     * FREE_STEP_START_PAGE: 1056 type=2 first pages, 38016 cells.
     * x2-x4 (ids 12-14) are 0 on free starts.
     */
    public static final int[] DEFAULT_FREE_START_WEIGHTS = {
            3846, 3695, 3779, 3777, 3645, 3654, 3618, 3710, 3732, 3704,
            439, 0, 0, 0, 108, 78, 47, 32, 57, 47, 32, 10, 6
    };

    private final Random random;
    private final int[] paidStart;
    private final int[] paidCascade;
    private final int[] freeStart;
    private final int[] freeCascade;
    private final boolean boostFirstColumnScatter;

    public HotpotBoardGenerator() {
        this(new SecureRandom());
    }

    public HotpotBoardGenerator(Random random) {
        this(random, DEFAULT_PAID_START_WEIGHTS, DEFAULT_CASCADE_WEIGHTS, DEFAULT_FREE_START_WEIGHTS);
    }

    public HotpotBoardGenerator(Random random, int[] paidStart, int[] cascade, int[] freeStart) {
        this(random, paidStart, cascade, freeStart, false);
    }

    public HotpotBoardGenerator(Random random, int[] paidStart, int[] cascade, int[] freeStart,
                                boolean boostFirstColumnScatter) {
        if (random == null) throw new IllegalArgumentException("random is required");
        this.random = random;
        this.paidStart = validated(paidStart, "paid-start");
        this.paidCascade = maskPaid(validated(cascade, "cascade"));
        this.freeStart = maskFree(validated(freeStart, "free-start"));
        this.freeCascade = maskFree(validated(cascade, "free-cascade"));
        this.boostFirstColumnScatter = boostFirstColumnScatter;
    }

    public static int[] defaultPaidStartWeights() { return DEFAULT_PAID_START_WEIGHTS.clone(); }
    public static int[] defaultCascadeWeights() { return DEFAULT_CASCADE_WEIGHTS.clone(); }
    public static int[] defaultFreeStartWeights() { return DEFAULT_FREE_START_WEIGHTS.clone(); }
    public static final int SPECIAL_TRIGGER_WEIGHT_MULTIPLIER = 10;

    /** 特殊入口只放大付费首局 Scatter 概率 *10。连消/免费仍走原权重。 */
    public static int[] specialEntryOpeningWeights(int[] ordinary) {
        if (ordinary == null || ordinary.length != SYMBOL_COUNT) {
            throw new IllegalArgumentException("ordinary opening weights must contain IDs 1..23");
        }
        int scatterIndex = HotpotResultUtil.SCATTER - 1;
        if (ordinary[scatterIndex] <= 0) {
            throw new IllegalArgumentException("paid-start Scatter weight must stay positive");
        }
        int[] boosted = ordinary.clone();
        boosted[scatterIndex] = Math.multiplyExact(ordinary[scatterIndex], SPECIAL_TRIGGER_WEIGHT_MULTIPLIER);
        return boosted;
    }

    public HotpotBoard generate(HotpotSymbolScene scene) {
        int[] prop = new int[HotpotBoard.SIZE];
        boolean seenTrigger = false;
        for (int i = 0; i < prop.length; i++) {
            if (i % HotpotBoard.ROWS == 0) seenTrigger = false;
            int symbol = nextAllowed(scene, prop, i, maxScatterOnPage(scene), seenTrigger);
            if (symbol == HotpotResultUtil.SCATTER) seenTrigger = true;
            prop[i] = symbol;
        }
        return new HotpotBoard(prop);
    }

    /**
     * Constrained ordinary-loss candidate: each pay symbol at most 7, Scatter at most 2.
     * ResultUtil must still independently accept it.
     */
    public HotpotBoard generateIndependentLossCandidate() {
        return generateIndependentLossCandidate(HotpotSpinMode.PAID);
    }

    public HotpotBoard generateIndependentLossCandidate(HotpotSpinMode mode) {
        java.util.Objects.requireNonNull(mode, "mode");
        int maxScatter = (mode == HotpotSpinMode.FREE
                ? HotpotResultUtil.FREE_SCATTER_RETRIGGER : HotpotResultUtil.PAID_SCATTER_TRIGGER) - 1;
        int[] lossWeights = mode == HotpotSpinMode.FREE ? freeStart : paidStart;
        int[] prop = new int[HotpotBoard.SIZE];
        int[] payCounts = new int[11];
        int scatter = 0;
        for (int i = 0; i < prop.length; i++) {
            int symbol = nextAllowedLossSymbol(prop, i, payCounts, scatter, maxScatter, lossWeights);
            prop[i] = symbol;
            if (symbol >= HotpotResultUtil.MIN_PAY_SYMBOL && symbol <= HotpotResultUtil.MAX_PAY_SYMBOL) {
                payCounts[symbol]++;
            }
            if (symbol == HotpotResultUtil.SCATTER) scatter++;
        }
        return new HotpotBoard(prop);
    }

    public HotpotBoard cascade(HotpotBoard board, HotpotEvaluation evaluation, HotpotSymbolScene fillScene) {
        boolean[] removed = HotpotResultUtil.eliminatedMask(board, evaluation);
        int[] oldProp = board.getProp();
        int[] next = new int[HotpotBoard.SIZE];
        for (int col = 0; col < HotpotBoard.COLUMNS; col++) {
            int[] keep = new int[HotpotBoard.ROWS];
            int kept = 0;
            for (int row = 0; row < HotpotBoard.ROWS; row++) {
                int index = col * HotpotBoard.ROWS + row;
                if (!removed[index]) keep[kept++] = oldProp[index];
            }
            int fill = HotpotBoard.ROWS - kept;
            for (int i = 0; i < kept; i++) {
                next[col * HotpotBoard.ROWS + fill + i] = keep[i];
            }
        }
        // Place every survivor first, including later columns, before reserving new Scatter.
        for (int index = 0; index < next.length; index++) {
            if (next[index] == 0)
                next[index] = nextAllowedFill(fillScene, next, index, 0, maxScatterOnPage(fillScene));
        }
        return new HotpotBoard(next);
    }

    public int nextSymbol(HotpotSymbolScene scene) {
        if (scene == null) throw new IllegalArgumentException("symbol scene is required");
        int[] weights = weightsFor(scene);
        return pick(weights);
    }

    private int[] weightsFor(HotpotSymbolScene scene) {
        switch (scene) {
            case PAID_START -> { return paidStart; }
            case PAID_CASCADE -> { return paidCascade; }
            case FREE_START -> { return freeStart; }
            case FREE_CASCADE -> { return freeCascade; }
        }
        throw new IllegalStateException("unhandled symbol scene: " + scene);
    }

    private int nextAllowed(HotpotSymbolScene scene, int[] prop, int index, int maxBoardScatter,
                            boolean columnHasTrigger) {
        int[] weights = weightsForDraw(scene, columnHasTrigger);
        for (int attempt = 0; attempt < 32; attempt++) {
            int symbol = pick(weights);
            if (symbol != HotpotResultUtil.SCATTER) return symbol;
            if (scatterAllowed(prop, index, 0, maxBoardScatter)) return symbol;
        }
        return pickNonScatter(scene);
    }

    private int[] weightsForDraw(HotpotSymbolScene scene, boolean columnHasTrigger) {
        if (boostFirstColumnScatter && scene == HotpotSymbolScene.PAID_START && !columnHasTrigger) {
            return specialEntryOpeningWeights(paidStart);
        }
        return weightsFor(scene);
    }

    private int nextAllowedFill(HotpotSymbolScene scene, int[] next, int index, int keepScatter,
                                int maxBoardScatter) {
        for (int attempt = 0; attempt < 32; attempt++) {
            int symbol = pick(weightsFor(scene));
            if (symbol != HotpotResultUtil.SCATTER) return symbol;
            if (scatterAllowed(next, index, keepScatter, maxBoardScatter)) return symbol;
        }
        return pickNonScatter(scene);
    }

    private int nextAllowedLossSymbol(int[] prop, int index, int[] payCounts, int scatter, int maxScatter, int[] lossWeights) {
        for (int attempt = 0; attempt < 64; attempt++) {
            int symbol = pick(lossWeights);
            if (symbol >= HotpotResultUtil.MIN_PAY_SYMBOL && symbol <= HotpotResultUtil.MAX_PAY_SYMBOL
                    && payCounts[symbol] >= 7) continue;
            if (symbol == HotpotResultUtil.SCATTER) {
                if (scatter >= maxScatter) continue;
                if (!scatterAllowed(prop, index, 0, maxScatter)) continue;
            }
            return symbol;
        }
        for (int symbol = HotpotResultUtil.MIN_PAY_SYMBOL; symbol <= HotpotResultUtil.MAX_PAY_SYMBOL; symbol++) {
            if (payCounts[symbol] < 7) return symbol;
        }
        throw new IllegalStateException("cannot pick a loss-safe symbol");
    }

    private static int maxScatterOnPage(HotpotSymbolScene scene) {
        if (scene == HotpotSymbolScene.FREE_START || scene == HotpotSymbolScene.FREE_CASCADE) {
            // 2+ Scatter on a free page is a legal origin retrigger, but after GetFreeTimesView
            // the original Game1830 page enters ADDSCATTER / FreeSpinWon and never resumes BetClick.
            return HotpotResultUtil.FREE_SCATTER_RETRIGGER - 1;
        }
        return HotpotResultUtil.MAX_SCATTER_PAID_PAGE;
    }

    private static boolean scatterAllowed(int[] prop, int index, int extraColumnScatter, int maxBoardScatter) {
        int col = index / HotpotBoard.ROWS;
        int inColumn = extraColumnScatter;
        int onBoard = extraColumnScatter;
        int colStart = col * HotpotBoard.ROWS;
        for (int i = 0; i < prop.length; i++) {
            if (prop[i] != HotpotResultUtil.SCATTER) continue;
            onBoard++;
            if (i >= colStart && i < colStart + HotpotBoard.ROWS) inColumn++;
        }
        return inColumn < HotpotResultUtil.MAX_SCATTER_PER_COLUMN && onBoard < maxBoardScatter;
    }

    private int pickNonScatter(HotpotSymbolScene scene) {
        int[] weights = weightsFor(scene).clone();
        weights[HotpotResultUtil.SCATTER - 1] = 0;
        return pick(weights);
    }

    private int pick(int[] weights) {
        int total = 0;
        for (int weight : weights) total += weight;
        int value = random.nextInt(total);
        for (int i = 0; i < weights.length; i++) {
            value -= weights[i];
            if (value < 0) return i + 1;
        }
        throw new IllegalStateException("weight pick exhausted");
    }

    private static int[] maskPaid(int[] weights) {
        int[] copy = weights.clone();
        for (int id = 16; id <= 23; id++) copy[id - 1] = 0;
        return requirePositiveTotal(copy, "paid-cascade");
    }

    private static int[] maskFree(int[] weights) {
        int[] copy = weights.clone();
        copy[11] = 0;
        copy[12] = 0;
        copy[13] = 0;
        return requirePositiveTotal(copy, "free");
    }

    private static int[] validated(int[] values, String name) {
        if (values == null || values.length != SYMBOL_COUNT) {
            throw new IllegalArgumentException(name + " weights must contain IDs 1..23");
        }
        int[] copy = values.clone();
        int total = 0;
        for (int value : copy) {
            if (value < 0) throw new IllegalArgumentException(name + " weight must be >= 0");
            total = Math.addExact(total, value);
        }
        if (total <= 0) throw new IllegalArgumentException(name + " weight total must be > 0");
        return copy;
    }

    private static int[] requirePositiveTotal(int[] weights, String name) {
        int total = 0;
        for (int value : weights) total += value;
        if (total <= 0) throw new IllegalArgumentException(name + " masked weight total must be > 0");
        return weights;
    }
}
