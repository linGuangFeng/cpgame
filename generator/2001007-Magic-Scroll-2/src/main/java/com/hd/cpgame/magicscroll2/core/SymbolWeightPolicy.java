package com.hd.cpgame.magicscroll2.core;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Magic Scroll 2 本地复刻牌面权重。原厂服务端权重未公开，这些权重不代表原厂 RTP；
 * 正式 Loader 必须从 generator.properties 显式读取并传入，禁止隐式均匀抽牌。
 */
public final class SymbolWeightPolicy {
    private static final int FIRST_REGULAR_SYMBOL = 3;
    private static final int LAST_REGULAR_SYMBOL = 12;
    private final int[] weights;
    private final int emptyMaterialWeight;
    private final int dirtMaterialWeight;

    public SymbolWeightPolicy(int[] regularSymbolWeights) {
        this(regularSymbolWeights, 30587, 34776);
    }

    public SymbolWeightPolicy(int[] regularSymbolWeights, int emptyMaterialWeight, int dirtMaterialWeight) {
        if (regularSymbolWeights == null
                || regularSymbolWeights.length != LAST_REGULAR_SYMBOL - FIRST_REGULAR_SYMBOL + 1) {
            throw new IllegalArgumentException("Magic Scroll 2 regular symbol weights 3..12 are required");
        }
        this.weights = regularSymbolWeights.clone();
        long total = 0;
        for (int weight : this.weights) {
            if (weight < 1) throw new IllegalArgumentException("all current-game symbol weights must be positive");
            total += weight;
        }
        if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("symbol weights are too large");
        if (emptyMaterialWeight < 1 || dirtMaterialWeight < 1) {
            throw new IllegalArgumentException("empty and dirt material weights must both be positive");
        }
        this.emptyMaterialWeight = emptyMaterialWeight;
        this.dirtMaterialWeight = dirtMaterialWeight;
    }

    public static SymbolWeightPolicy localReplicaDefaults() {
        return new SymbolWeightPolicy(new int[]{2008, 3157, 4024, 4579, 5893,
                6891, 8029, 10511, 10623, 10243}, 30587, 34776);
    }

    int select(SecureRandom random) { return selectExcluding(random, new HashSet<Integer>()); }

    int selectExcluding(SecureRandom random, Set<Integer> excluded) {
        if (random == null || excluded == null) throw new IllegalArgumentException("random and exclusions are required");
        int total = 0;
        for (int symbol = FIRST_REGULAR_SYMBOL; symbol <= LAST_REGULAR_SYMBOL; symbol++) {
            if (!excluded.contains(symbol)) total += weights[symbol - FIRST_REGULAR_SYMBOL];
        }
        if (total <= 0) throw new IllegalArgumentException("all weighted symbols were excluded");
        int selected = random.nextInt(total);
        for (int symbol = FIRST_REGULAR_SYMBOL; symbol <= LAST_REGULAR_SYMBOL; symbol++) {
            if (excluded.contains(symbol)) continue;
            selected -= weights[symbol - FIRST_REGULAR_SYMBOL];
            if (selected < 0) return symbol;
        }
        throw new IllegalStateException("weighted selection did not resolve");
    }

    List<Integer> weightedPermutation(SecureRandom random) {
        List<Integer> result = new ArrayList<Integer>();
        Set<Integer> excluded = new HashSet<Integer>();
        while (result.size() < weights.length) {
            int symbol = selectExcluding(random, excluded);
            result.add(symbol);
            excluded.add(symbol);
        }
        return result;
    }

    public int weightFor(int symbol) {
        if (symbol < FIRST_REGULAR_SYMBOL || symbol > LAST_REGULAR_SYMBOL) {
            throw new IllegalArgumentException("regular symbol must be 3..12");
        }
        return weights[symbol - FIRST_REGULAR_SYMBOL];
    }

    public int[] copyWeights() { return weights.clone(); }

    int selectEmptyMaterial(SecureRandom random) {
        return random.nextInt(emptyMaterialWeight + dirtMaterialWeight) < emptyMaterialWeight ? 0 : 1;
    }

    public int getEmptyMaterialWeight() { return emptyMaterialWeight; }
    public int getDirtMaterialWeight() { return dirtMaterialWeight; }
}
