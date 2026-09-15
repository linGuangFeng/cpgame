package com.cpgame.batchc.cybergo;

import java.math.BigDecimal;

/** 只包含生成链路实际使用的通用安全上限，不承载证据说明或概率。 */
public record GenerationLimits(
        BigDecimal ordinaryMaxWinMultiplier,
        BigDecimal freeSpinsMaxWinMultiplier,
        int freeSpinsMaxSteps,
        int lossConstructiveAttempts,
        int lossFallbackSamples,
        SymbolWeights symbolWeights) {

    public GenerationLimits {
        requirePositive(ordinaryMaxWinMultiplier, "普通局累计最大中奖倍数");
        requirePositive(freeSpinsMaxWinMultiplier, "免费局累计最大中奖倍数");
        if (freeSpinsMaxSteps < 1) throw new IllegalArgumentException("免费Step上限必须大于0");
        if (lossConstructiveAttempts < 1) throw new IllegalArgumentException("构造式LOSS尝试次数必须大于0");
        if (lossFallbackSamples < 1) throw new IllegalArgumentException("LOSS回退样本数必须大于0");
        if (symbolWeights == null) throw new IllegalArgumentException("符号权重不能为空");
    }

    public static GenerationLimits defaults() {
        return new GenerationLimits(new BigDecimal("20000"), new BigDecimal("20000"), 20, 5, 10,
                SymbolWeights.localDefaults());
    }

    public BigDecimal maxWinMultiplier(CyberGoModels.RoundKind kind) {
        return kind == CyberGoModels.RoundKind.FREE_SPINS
                ? freeSpinsMaxWinMultiplier : ordinaryMaxWinMultiplier;
    }

    private static void requirePositive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException(name + "必须大于0");
    }
}
