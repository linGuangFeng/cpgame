package com.hd.cpgame.riocarnival.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

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

    /** 规则整数倍：奖金/(betSize×betLevel)，线数不进分母。 */
    public BigDecimal awardMultiplier(GeneratedRound round) {
        BigDecimal unit = round.betSize.multiply(BigDecimal.valueOf(round.betLevel));
        if (unit.signum() == 0) return BigDecimal.ZERO;
        return totalAward.divide(unit, 8, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    /** Redis 倍率索引 = 奖金/(betSize×betLevel) 整数；不再 ×100，也不除总注（含线数）。 */
    public int redisRatio(GeneratedRound round) {
        BigDecimal unit = round.betSize.multiply(BigDecimal.valueOf(round.betLevel));
        if (unit.signum() == 0 || totalAward.signum() == 0) return 0;
        try {
            return totalAward.divide(unit, 0, RoundingMode.UNNECESSARY).intValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalStateException("当前完整局无法精确落入整数 Redis 倍率桶（单位=betSize×betLevel）", ex);
        }
    }
}
