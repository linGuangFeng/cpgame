package com.cpgame.crazybirds.generator;

import com.cpgame.crazybirds.generator.model.ResultAnalysis;
import com.cpgame.crazybirds.generator.model.RoundMode;
import com.cpgame.crazybirds.generator.model.RoundResult;
import com.cpgame.crazybirds.generator.model.SpinStep;
import com.cpgame.crazybirds.generator.model.WinWay;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public final class RoundVerifier {
    public ResultAnalysis verify(RoundResult round) {
        if (round.steps().isEmpty()) throw new IllegalArgumentException("完整局不能没有 Step");
        BigDecimal bet = GameRules.betAmount(round.bl(), round.bs());
        if (round.betAmount().compareTo(bet) != 0) throw new IllegalArgumentException("ba 与 bl*bs 不一致");
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < round.steps().size(); i++) {
            SpinStep step = round.steps().get(i);
            ResultUtil.validateBoard(step.rskl());
            List<WinWay> wins = ResultUtil.evaluateWays(step.rskl(), bet);
            BigDecimal wa = ResultUtil.payout(wins);
            if (wa.compareTo(step.wa().setScale(2, RoundingMode.HALF_UP)) != 0) {
                throw new IllegalArgumentException("Step " + (i + 1) + " wa 与 ResultUtil 不一致");
            }
            total = total.add(wa);
        }
        total = total.setScale(2, RoundingMode.HALF_UP);
        if (total.compareTo(round.totalWin().setScale(2, RoundingMode.HALF_UP)) != 0) {
            throw new IllegalArgumentException("整局 wa 合计不一致");
        }
        boolean free = round.steps().stream().anyMatch(s -> s.fsn() > 0);
        RoundMode mode = free ? RoundMode.FREE_SPINS
                : total.signum() > 0 ? RoundMode.ORDINARY_WIN : RoundMode.ORDINARY_LOSS;
        if (mode != round.mode()) throw new IllegalArgumentException("RoundMode 与开奖结果不一致");
        BigDecimal multiplier = bet.signum() == 0 ? BigDecimal.ZERO
                : total.divide(bet, 0, RoundingMode.DOWN).setScale(0);
        return new ResultAnalysis(mode, multiplier, round.steps().size());
    }

    public void verifyRecovery(RoundResult original, RoundResult rebuilt) {
        verify(original);
        verify(rebuilt);
        if (original.boards().size() != rebuilt.boards().size()) {
            throw new IllegalArgumentException("恢复后 Step 数不一致");
        }
        for (int i = 0; i < original.boards().size(); i++) {
            if (!original.boards().get(i).equals(rebuilt.boards().get(i))) {
                throw new IllegalArgumentException("恢复后牌面不一致");
            }
        }
    }
}
