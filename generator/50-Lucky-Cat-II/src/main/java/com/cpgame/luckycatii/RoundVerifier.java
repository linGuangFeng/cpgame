package com.cpgame.luckycatii;

import com.cpgame.luckycatii.model.ResultAnalysis;
import com.cpgame.luckycatii.model.RoundResult;

import java.math.BigDecimal;

/** Compares RoundFactory truth with the independent ResultUtil inference. */
public final class RoundVerifier {
    public ResultAnalysis verify(RoundResult round) {
        ResultAnalysis inferred = ResultUtil.analyze(round);
        require(inferred.gameMode() == round.gameMode(), "gm 不一致");
        require(inferred.respinReelIndex() == round.respinReelIndex(), "rdri 不一致");
        require(inferred.rpx() == round.rpx(), "rpx 不一致");
        require(inferred.winningLines().equals(round.winningLines()), "wmkl 不一致");
        require(inferred.award().compareTo(round.award()) == 0, "wa 不一致");
        require(inferred.betAmount().compareTo(round.betAmount()) == 0, "ba 不一致");
        require(inferred.terminal(), "完整局必须终态");
        return inferred;
    }

    public void verifyRecovery(RoundResult original, RoundResult restored) {
        verify(original);
        verify(restored);
        require(original.gameMode() == restored.gameMode(), "恢复 gm 不一致");
        require(original.rpx() == restored.rpx(), "恢复 rpx 不一致");
        require(original.paidBoard().equals(restored.paidBoard()), "恢复 S01 不一致");
        require(original.finalBoard().equals(restored.finalBoard()), "恢复终盘不一致");
        require(original.winningLines().equals(restored.winningLines()), "恢复 wmkl 不一致");
        require(original.award().compareTo(restored.award()) == 0, "恢复 wa 不一致");
        require(original.steps().size() == restored.steps().size(), "恢复步骤数不一致");
    }

    public void verifyLimits(RoundResult round, BigDecimal maxTotalWinMultiplier) {
        ResultAnalysis inferred = verify(round);
        BigDecimal ratio = inferred.betAmount().signum() == 0 ? BigDecimal.ZERO
                : inferred.award().divide(inferred.betAmount());
        require(ratio.compareTo(maxTotalWinMultiplier) <= 0, "完整局累计中奖倍数超限");
        if (inferred.luckyRespin()) {
            require(round.steps().size() == 1 + GameRules.MAX_LUCKY_RESPINS, "Lucky Respin 次数超限");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
