package com.hd.cpgame.riocarnival.core;

import java.math.BigDecimal;

/** ResultUtil 从原始牌面反推得到的只读结论。 */
public final class RoundResult {
    public final String mode;
    public final BigDecimal totalAward;
    public final int initialFreeSpins;
    public final int freeMultiplier;
    public final int freeStepCount;
    public final int retriggerCount;
    public final int maximumConsecutiveWins;

    RoundResult(String mode, BigDecimal totalAward, int initialFreeSpins, int freeMultiplier,
                int freeStepCount, int retriggerCount, int maximumConsecutiveWins) {
        this.mode = mode;
        this.totalAward = totalAward;
        this.initialFreeSpins = initialFreeSpins;
        this.freeMultiplier = freeMultiplier;
        this.freeStepCount = freeStepCount;
        this.retriggerCount = retriggerCount;
        this.maximumConsecutiveWins = maximumConsecutiveWins;
    }

    public BigDecimal awardMultiplier(GeneratedRound round) {
        if (round.totalBet().signum() == 0) return BigDecimal.ZERO;
        return totalAward.divide(round.totalBet(), 8, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
    }

    /** 下游 Redis 的实际倍率索引，单位为 0.01x（总派彩 / 总投注 * 100）。 */
    public int redisRatio(GeneratedRound round) {
        if (round.totalBet().signum() == 0 || totalAward.signum() == 0) return 0;
        try {
            return totalAward.multiply(BigDecimal.valueOf(100L)).divide(round.totalBet())
                .stripTrailingZeros().intValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalStateException("当前完整局无法精确落入 0.01x Redis 倍率桶", ex);
        }
    }
}
