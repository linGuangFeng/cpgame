package com.hd.pg.appapi.business.vo.cpgame.luckypanda;

/**
 * Inner-main RLE heights 1..4, training-only (holdout = every 13th roundOrdinal).
 * Cascade/free-cascade counts are newly dealt tokens, not surviving stacks.
 * Evidence: reports/41-Lucky-Panda/training-holdout-weights.json
 */
public final class LuckyPandaHeightModel {
    private static final int[] PAID_START = {9258, 3522, 1838, 696};
    private static final int[] CASCADE_REFILL = {5517, 180, 122, 26};
    private static final int[] FREE_START = {2576, 961, 506, 196};
    private static final int[] FREE_CASCADE_REFILL = {1441, 45, 38, 7};

    private LuckyPandaHeightModel() { }

    public static int[] innerMainHeights(WeightScene scene) {
        return switch (scene) {
            case PAID_START -> PAID_START.clone();
            case CASCADE_REFILL -> CASCADE_REFILL.clone();
            case FREE_START -> FREE_START.clone();
            case FREE_CASCADE_REFILL -> FREE_CASCADE_REFILL.clone();
        };
    }
}
