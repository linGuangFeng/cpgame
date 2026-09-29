package com.cpgame.crazybirds.generator;

import com.cpgame.crazybirds.generator.model.RoundMode;
import com.cpgame.crazybirds.generator.model.RoundResult;
import com.cpgame.crazybirds.generator.model.SpinStep;
import com.cpgame.crazybirds.generator.model.WinWay;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class RoundFactory {
    public RoundResult restore(String roundKey, BigDecimal bs, int bl, BigDecimal start, List<List<String>> boards) {
        if (boards == null || boards.isEmpty()) throw new IllegalArgumentException("完整局不能没有牌面");
        ResultUtil.validateBoardForStage(boards.get(0), false);
        int scatterReels = ResultUtil.scatterReels(boards.get(0));
        int awardedFreeSpins = scatterReels >= GameRules.SCATTER_TRIGGER_REELS
                ? GameRules.freeSpinsForScatterReels(scatterReels) : 0;
        int expectedBoards = awardedFreeSpins == 0 ? 1 : awardedFreeSpins + 1;
        if (boards.size() != expectedBoards) {
            throw new IllegalArgumentException("完整局 Step 数与 Scatter 奖励不一致: expected="
                    + expectedBoards + ", actual=" + boards.size());
        }
        BigDecimal bet = GameRules.betAmount(bl, bs);
        List<SpinStep> steps = new ArrayList<>();
        BigDecimal pb = start;
        BigDecimal rwa = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        int fsn = awardedFreeSpins;
        boolean free = awardedFreeSpins > 0;
        for (int i = 0; i < boards.size(); i++) {
            List<String> board = boards.get(i);
            ResultUtil.validateBoardForStage(board, i > 0);
            List<WinWay> wins = ResultUtil.evaluateWays(board, bet);
            BigDecimal wa = ResultUtil.payout(wins);
            if (i > 0 && ResultUtil.isScatterTrigger(board)) {
                throw new IllegalArgumentException("DISABLED_BY_DEFAULT_POLICY: 免费中再次触发");
            }
            int nfsc = free ? Math.min(i, fsn) : 0;
            if (i == 0) pb = pb.subtract(bet).add(wa);
            else pb = pb.add(wa);
            rwa = rwa.add(wa);
            boolean last = i == boards.size() - 1;
            int ss = last ? 1 : 0;
            int gt = (free && i > 0) ? 2 : 1;
            int sgt = (free && i > 0) ? 2 : 0;
            Map<Integer, Integer> pxl = ResultUtil.pxlFromBoard(board);
            steps.add(new SpinStep(
                    board,
                    ResultUtil.wmklOf(wins),
                    ResultUtil.wsklOf(wins),
                    pxl,
                    i == 0 ? bet : BigDecimal.ZERO,
                    wa,
                    rwa,
                    pb.setScale(2, RoundingMode.HALF_UP),
                    ss,
                    free ? fsn : 0,
                    nfsc,
                    gt,
                    sgt
            ));
        }
        RoundMode mode;
        if (free) mode = RoundMode.FREE_SPINS;
        else if (rwa.signum() > 0) mode = RoundMode.ORDINARY_WIN;
        else mode = RoundMode.ORDINARY_LOSS;
        return new RoundResult(roundKey, bs, bl, start, bet, rwa, mode, List.copyOf(steps));
    }
}
