package com.cpgame.glacier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Empirical 1780 dealing model. Defaults match origin-gap-fill-20260908 first pages / new symbols.
 * Loader injects generator.properties so every published weight is actually used.
 */
public final class GenerationModel {
    public static final int[] DEFAULT_PAID_INITIAL = {230, 2268, 2248, 2119, 2188, 2135, 2134, 2185, 2088, 2177, 2203, 383, 53};
    public static final int[] DEFAULT_PAID_REFILL = {50, 767, 699, 624, 596, 565, 589, 607, 632, 587, 593, 103, 49};
    public static final int[] DEFAULT_FREE_INITIAL = {11, 145, 143, 163, 136, 158, 140, 136, 137, 160, 145, 33, 3};
    public static final int[] DEFAULT_FREE_REFILL = {4, 73, 111, 91, 82, 73, 73, 70, 59, 79, 75, 20, 4};
    public static final int[] DEFAULT_SILVER_TO_GOLD = {0, 20, 18, 9, 16, 14, 15, 19, 11, 20, 26};
    /** @deprecated use instance fields; kept for existing static cap references in tests */
    public static final int MAX_SCATTER_UNITS_BOARD = 4;
    public static final int MAX_SCATTER_UNITS_REEL = 3;
    public static final int MAX_SCATTER_SYMBOLS_COLUMN = 1;
    public static final int MAX_SCATTER_SYMBOLS_FREE = 3;
    public static final int MAX_WILD_BOARD = 3;
    public static final int MAX_WILD_REEL = 2;
    public static final int MAX_CASCADE_PAGES = 14;
    public static final int MAX_FREE_SPINS = 16;
    public static final int SPECIAL_SCATTER_BOOST = 10;

    public final int[] paidInitial, paidRefill, freeInitial, freeRefill, silverToGold;
    public final String[] innerSignatures;
    public final int[] innerWeights;
    public final int maxScatterUnitsBoard, maxScatterUnitsReel, maxScatterSymbolsColumn, maxScatterSymbolsFree;
    public final int maxWildBoard, maxWildReel, maxCascadePages, maxFreeSpins, specialScatterBoost;

    public record Segment(int height, boolean silver) {}

    public GenerationModel() {
        this(DEFAULT_PAID_INITIAL, DEFAULT_PAID_REFILL, DEFAULT_FREE_INITIAL, DEFAULT_FREE_REFILL,
            DEFAULT_SILVER_TO_GOLD, GeneratorConfiguration.STRUCTURES, defaultInnerWeights(),
            4, 3, 1, 3, 3, 2, 14, 16, 10);
    }

    GenerationModel(GeneratorConfiguration c) {
        this(c.paidInitial, c.paidRefill, c.freeInitial, c.freeRefill, c.silverToGold,
            GeneratorConfiguration.STRUCTURES, structureWeights(c.paidInitialStructures),
            c.maxScatterUnitsBoard, c.maxScatterUnitsReel, c.maxScatterSymbolsColumn, c.maxScatterSymbolsFree,
            c.maxWildBoard, c.maxWildReel, c.maxCascadePages, c.maxMarySpins, c.specialScatterBoost);
    }

    private GenerationModel(int[] paidInitial, int[] paidRefill, int[] freeInitial, int[] freeRefill,
                            int[] silverToGold, String[] innerSignatures, int[] innerWeights,
                            int maxScatterUnitsBoard, int maxScatterUnitsReel, int maxScatterSymbolsColumn,
                            int maxScatterSymbolsFree, int maxWildBoard, int maxWildReel,
                            int maxCascadePages, int maxFreeSpins, int specialScatterBoost) {
        this.paidInitial = paidInitial.clone();
        this.paidRefill = paidRefill.clone();
        this.freeInitial = freeInitial.clone();
        this.freeRefill = freeRefill.clone();
        this.silverToGold = silverToGold.clone();
        this.innerSignatures = innerSignatures.clone();
        this.innerWeights = innerWeights.clone();
        this.maxScatterUnitsBoard = maxScatterUnitsBoard;
        this.maxScatterUnitsReel = maxScatterUnitsReel;
        this.maxScatterSymbolsColumn = maxScatterSymbolsColumn;
        this.maxScatterSymbolsFree = maxScatterSymbolsFree;
        this.maxWildBoard = maxWildBoard;
        this.maxWildReel = maxWildReel;
        this.maxCascadePages = maxCascadePages;
        this.maxFreeSpins = maxFreeSpins;
        this.specialScatterBoost = specialScatterBoost;
    }

    private static int[] defaultInnerWeights() {
        return new int[] {
            320, 250, 249, 192, 190, 186, 142, 127, 120, 115,
            102, 101, 101, 95, 87, 86, 81, 74, 64, 59,
            59, 58, 48, 45, 41, 40, 38, 36, 35, 34,
            31, 30, 29, 26, 25, 21, 21, 15, 15
        };
    }

    private static int[] structureWeights(Map<String, Integer> map) {
        int[] out = new int[GeneratorConfiguration.STRUCTURES.length];
        for (int i = 0; i < out.length; i++) out[i] = map.getOrDefault(GeneratorConfiguration.STRUCTURES[i], 0);
        return out;
    }

    public int nextProp(Random random, int[] weights, boolean edge, boolean allowScatter, boolean allowWild) {
        long total = 0;
        long[] w = new long[13];
        for (int i = 0; i < 13; i++) {
            int prop = i + 1;
            if (edge && prop == GameRuleCore.WILD) continue;
            if (!allowScatter && prop == GameRuleCore.SCATTER) continue;
            if (!allowWild && prop == GameRuleCore.WILD) continue;
            if (weights[i] <= 0) continue;
            w[i] = weights[i];
            total += w[i];
        }
        if (total <= 0) return 2;
        long v = nextLong(random, total);
        for (int i = 0; i < 13; i++) {
            v -= w[i];
            if (v < 0) return i + 1;
        }
        return 2;
    }

    public int goldProp(Random random) {
        long total = 0;
        for (int x : silverToGold) total += x;
        long v = nextLong(random, total);
        for (int i = 0; i < silverToGold.length; i++) {
            v -= silverToGold[i];
            if (v < 0) return i + 1;
        }
        return 11;
    }

    public List<Segment> innerStructure(Random random) {
        int total = 0;
        for (int x : innerWeights) total += x;
        int v = random.nextInt(Math.max(1, total));
        String sig = innerSignatures[0];
        for (int i = 0; i < innerSignatures.length; i++) {
            v -= innerWeights[i];
            if (v < 0) {
                sig = innerSignatures[i];
                break;
            }
        }
        var out = new ArrayList<Segment>();
        for (String tok : sig.split("-")) {
            int h = tok.charAt(0) - '0';
            boolean silver = tok.length() > 1 && tok.charAt(1) == 'S';
            out.add(new Segment(h, silver && h >= 2));
        }
        return out;
    }

    public List<Segment> refillSegments(int cells) {
        var out = new ArrayList<Segment>();
        for (int i = 0; i < cells; i++) out.add(new Segment(1, false));
        return out;
    }

    public int[] boostScatter(int[] weights) {
        int[] out = weights.clone();
        out[GameRuleCore.SCATTER - 1] = Math.multiplyExact(out[GameRuleCore.SCATTER - 1], specialScatterBoost);
        return out;
    }

    private static long nextLong(Random random, long bound) {
        long bits, value;
        do {
            bits = random.nextLong() >>> 1;
            value = bits % bound;
        } while (bits - value + (bound - 1) < 0L);
        return value;
    }
}
