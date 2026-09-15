package com.cpgame.batchc.cybergo;

import static com.cpgame.batchc.cybergo.CyberGoRules.MINIMUM_BET;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** 与RoundFactory分离的完整局校验器，负责结果反推后的类型与安全上限验证。 */
public final class CompleteRoundVerifier {
    private final GenerationLimits limits;

    public CompleteRoundVerifier(GenerationLimits limits) {
        this.limits = Objects.requireNonNull(limits);
    }

    public Verification verify(CyberGoModels.CompleteRound round) {
        ResultUtil.RoundResult result = ResultUtil.reverse(round);
        if (result.inferredKind() != round.kind()) throw new IllegalArgumentException("Round声明类型与独立反推不一致");
        if (round.bet().compareTo(MINIMUM_BET) != 0) throw new IllegalArgumentException("Round下注额不符合能力清单");
        if (result.totalWin().compareTo(round.totalWin()) != 0) throw new IllegalArgumentException("Round终态金额不守恒");
        if (round.deliveries().stream().anyMatch(step -> step.rpx() > 10)) throw new RoundLimitExceededException("Multiplier exceeds observed ceiling 10");
        if (result.freeSpinCount() > Math.min(15, limits.freeSpinsMaxSteps())) throw new RoundLimitExceededException("免费Step超过安全上限");
        BigDecimal multiplier = result.totalWin().divide(round.bet(), 8, RoundingMode.HALF_UP);
        if (multiplier.compareTo(limits.maxWinMultiplier(result.inferredKind())) > 0) {
            throw new RoundLimitExceededException("完整Round累计中奖倍数超过安全上限");
        }
        return new Verification(result.inferredKind(), result.totalWin(), multiplier,
                round.deliveries().size(), result.freeSpinCount());
    }

    public record Verification(CyberGoModels.RoundKind inferredKind, BigDecimal totalWin,
                               BigDecimal totalWinMultiplier, int deliveryCount, int freeSpinCount) { }

    public static final class RoundLimitExceededException extends IllegalArgumentException {
        public RoundLimitExceededException(String message) { super(message); }
    }
}
