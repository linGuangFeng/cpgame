package com.cpgame.curupira.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** 一次候选发牌使用的不可变权重；不包含目标结果或目标奖金。 */
public record GenerationPolicy(Map<Integer, Integer> symbolWeights) {
    public GenerationPolicy {
        symbolWeights = Map.copyOf(symbolWeights);
        if (!symbolWeights.keySet().equals(GameRules.SYMBOLS)) {
            throw new IllegalArgumentException("weights 必须覆盖 2350 全部符号");
        }
        if (symbolWeights.values().stream().anyMatch(value -> value == null || value <= 0)) {
            throw new IllegalArgumentException("weights 必须全部为正整数");
        }
    }

    /** 10 个连续付费原站样本（150 格）的合并计数，仅为样本经验权重。 */
    public static GenerationPolicy ordinaryPaidDefaults() {
        Map<Integer, Integer> weights = new LinkedHashMap<>();
        weights.put(1, 26);
        weights.put(2, 14);
        weights.put(3, 16);
        weights.put(4, 16);
        weights.put(11, 16);
        weights.put(12, 17);
        weights.put(13, 19);
        weights.put(14, 17);
        weights.put(GameRules.WILD, 6);
        weights.put(GameRules.SCATTER, 3);
        return new GenerationPolicy(weights);
    }
}
