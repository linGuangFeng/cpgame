package com.cpgame.crazypiggy.generator;

/** 构造式普通 LOSS 的通用运行参数。 */
public record LossGenerationPolicy(
        int constructiveAttempts,
        int fallbackSamples,
        int validationSamples,
        double minimumFirstAttemptSuccessRate) {

    public static LossGenerationPolicy defaults() {
        return new LossGenerationPolicy(5, 10, 100_000, 0.90d);
    }

    public LossGenerationPolicy {
        if (constructiveAttempts < 1 || fallbackSamples < 1 || validationSamples < 1) {
            throw new IllegalArgumentException("LOSS 尝试数与验证样本数必须大于 0");
        }
        if (minimumFirstAttemptSuccessRate <= 0 || minimumFirstAttemptSuccessRate > 1) {
            throw new IllegalArgumentException("LOSS 最低首次成功率必须在 (0,1] 内");
        }
    }
}
