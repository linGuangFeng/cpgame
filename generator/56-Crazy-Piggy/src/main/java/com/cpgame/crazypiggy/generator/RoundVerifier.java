package com.cpgame.crazypiggy.generator;

import com.cpgame.crazypiggy.generator.model.ResultAnalysis;
import com.cpgame.crazypiggy.generator.model.RoundResult;

import java.math.BigDecimal;

/** 将 RoundFactory 真值与 ResultUtil 独立反推结果逐字段比对。 */
public final class RoundVerifier {
    public ResultAnalysis verify(RoundResult round) {
        if (round.roundKey() == null || round.roundKey().isBlank() || round.createdAtEpochSecond() <= 0) {
            throw new IllegalStateException("完整局标识或创建时间无效");
        }
        ResultAnalysis inferred = ResultUtil.analyze(round);
        require(inferred.lineWins().equals(round.lineWins()), "wmkl/赔付线不一致");
        require(equal(inferred.betAmount(), round.betAmount()), "下注金额 ba 不一致");
        require(equal(inferred.baseAward(), round.baseAward()), "基础派奖不一致");
        require(equal(inferred.wheelAward(), round.wheelAward()), "轮盘增量派奖 fwa 不一致");
        require(equal(inferred.totalAward(), round.totalAward()), "完整局总派奖 wa 不一致");
        require(inferred.gameMode() == round.gameMode(), "gm 不一致");
        require(inferred.smallGameType() == round.smallGameType(), "small_game_type 不一致");
        require(inferred.deliveries().equals(round.deliveries()), "轮盘 Delivery 不连续或字段不一致");
        return inferred;
    }

    public void verifySettlement(BigDecimal previousBalance, BigDecimal postBalance, RoundResult round) {
        verify(round);
        BigDecimal expected = previousBalance.subtract(round.betAmount()).add(round.totalAward());
        require(equal(expected, postBalance), "余额不满足 previousPb-ba+wa");
    }

    public void verifyRecovery(RoundResult original, RoundResult restored) {
        verify(original);
        verify(restored);
        require(original.equals(restored), "最小事实恢复后的完整局字段不一致");
    }

    public void verifyLimits(RoundResult round, BigDecimal maxTotalWinMultiplier, int maxWheelDeliveries) {
        verify(round);
        BigDecimal multiplier = round.betAmount().signum() == 0 ? BigDecimal.ZERO
                : round.totalAward().divide(round.betAmount());
        require(multiplier.compareTo(maxTotalWinMultiplier) <= 0, "完整局累计中奖倍数超限");
        if (round.boosterWheel()) {
            require(round.deliveries().size() <= maxWheelDeliveries, "轮盘累计 Delivery 次数超限");
        }
    }

    private static boolean equal(BigDecimal left, BigDecimal right) {
        return left.compareTo(right) == 0;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
