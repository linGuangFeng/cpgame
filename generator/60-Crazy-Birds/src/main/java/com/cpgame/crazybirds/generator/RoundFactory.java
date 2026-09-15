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
        BigDecimal bet = GameRules.betAmount(bl, bs);
        List<SpinStep> steps = new ArrayList<>();
        BigDecimal pb = start;
        BigDecimal rwa = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        int fsn = 0;
        boolean free = false;
        for (int i = 0; i < boards.size(); i++) {
            List<String> board = boards.get(i);
            List<WinWay> wins = ResultUtil.evaluateWays(board, bet);
            BigDecimal wa = ResultUtil.payout(wins);
            boolean trigger = ResultUtil.isScatterTrigger(board);
            if (i == 0 && (trigger || boards.size() > 1)) {
                free = true;
                fsn = Math.max(GameRules.BASE_FREE_SPINS, boards.size() - 1);
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
