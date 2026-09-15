package com.cpgame.saci.generator;

import com.cpgame.saci.generator.model.ResultAnalysis;
import com.cpgame.saci.generator.model.RoundResult;

import java.math.BigDecimal;

public final class RoundVerifier {
    public ResultAnalysis verify(RoundResult round) {
        if (round.roundKey() == null || round.roundKey().isBlank()) {
            throw new IllegalStateException("完整局 roundKey 无效");
        }
        ResultAnalysis inferred = ResultUtil.analyze(round);
        if (inferred.mode() != round.mode()) throw new IllegalStateException("分类不一致");
        if (inferred.totalWin().compareTo(round.totalWin()) != 0) throw new IllegalStateException("完整局总派奖不一致");
        if (inferred.stepCount() != round.steps().size()) throw new IllegalStateException("Step 数不一致");
        return inferred;
    }

    public void verifyRecovery(RoundResult original, RoundResult restored) {
        verify(original);
        verify(restored);
        if (original.mode() != restored.mode()) throw new IllegalStateException("恢复后分类漂移");
        if (original.steps().size() != restored.steps().size()) throw new IllegalStateException("恢复后 Step 数漂移");
        for (int i = 0; i < original.steps().size(); i++) {
            if (!original.steps().get(i).rskl().equals(restored.steps().get(i).rskl())) {
                throw new IllegalStateException("恢复后牌面漂移 deliveryIndex=" + i);
            }
            if (!original.steps().get(i).wmkl().equals(restored.steps().get(i).wmkl())) {
                throw new IllegalStateException("恢复后 wmkl 漂移 deliveryIndex=" + i);
            }
            if (original.steps().get(i).wa().compareTo(restored.steps().get(i).wa()) != 0) {
                throw new IllegalStateException("恢复后 wa 漂移 deliveryIndex=" + i);
            }
        }
        if (original.totalWin().compareTo(restored.totalWin()) != 0) {
            throw new IllegalStateException("恢复后累计派奖漂移");
        }
    }

    public void verifyLimits(RoundResult round, BigDecimal maxTotalWinMultiplier, int maxSteps) {
        ResultAnalysis analysis = verify(round);
        BigDecimal multiplier = round.totalWin().divide(analysis.bet(), 8, java.math.RoundingMode.HALF_UP);
        if (multiplier.compareTo(maxTotalWinMultiplier) > 0) {
            throw new IllegalStateException("完整局累计中奖倍数超限");
        }
        if (round.steps().size() > maxSteps) throw new IllegalStateException("完整局 Step 数超限");
    }
}
