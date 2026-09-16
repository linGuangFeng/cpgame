package com.cpgame.replica.luckypanda;

import com.hd.pg.appapi.business.vo.cpgame.luckypanda.GameRuleCore;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaBoard;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaBoardGenerator;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaEvaluation;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaFrameAssigner;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaIndependentLossGenerator;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaResultUtil;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaRpxTracker;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.WeightScene;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** One complete paid Round. Random boards and ResultUtil stay separate. */
public final class CompleteRoundFactory {
    public static final class RoundRejectedException extends RuntimeException {
        public RoundRejectedException(String message) { super(message); }
    }

    public record GeneratedRound(CompleteRoundFact fact, BigDecimal terminalRwa, int actualMultiplier) { }

    private final BigDecimal betSize;
    private final int betLevel;

    public CompleteRoundFactory(BigDecimal betSize, int betLevel) {
        this.betSize = betSize;
        this.betLevel = betLevel;
    }

    public GeneratedRound generate(Random random, int maxConsecutiveWins, int maxMarySpins,
                                   Map<WeightScene, int[]> weights, boolean forceOrdinaryLoss) {
        return generate(random, maxConsecutiveWins, maxMarySpins, weights, forceOrdinaryLoss, false);
    }

    public GeneratedRound generate(Random random, int maxConsecutiveWins, int maxMarySpins,
                                   Map<WeightScene, int[]> weights, boolean forceOrdinaryLoss,
                                   boolean specialOpening) {
        if (maxConsecutiveWins < 1) throw new IllegalArgumentException("max-consecutive-wins must be >= 1");
        if (maxMarySpins < 1) throw new IllegalArgumentException("max-mary-spins must be >= 1");
        LuckyPandaBoardGenerator boards = new LuckyPandaBoardGenerator(random, weights, specialOpening);
        LuckyPandaBoard start;
        try {
            start = forceOrdinaryLoss
                    ? new LuckyPandaIndependentLossGenerator(boards, betSize, betLevel).generate(WeightScene.PAID_START)
                    : boards.generate(WeightScene.PAID_START);
        } catch (IllegalStateException rejected) {
            throw new RoundRejectedException(rejected.getMessage());
        }
        if (!GameRuleCore.withinCapturedCaps(start)) {
            throw new RoundRejectedException("paid start exceeded captured symbol caps");
        }
        LuckyPandaRpxTracker rpx = new LuckyPandaRpxTracker();
        Segment paid;
        try {
            paid = tumble(boards, random, rpx, 0, start, WeightScene.CASCADE_REFILL, maxConsecutiveWins,
                    GameRuleCore.SCAT_TOTAL_MAX_BLOCKS);
        } catch (IllegalStateException rejected) {
            throw new RoundRejectedException(rejected.getMessage());
        }
        List<List<CompleteRoundFact.PageFact>> freeSpins = List.of();
        LuckyPandaEvaluation terminal = paid.terminalEvaluation();
        if (LuckyPandaResultUtil.scatterFreeTrigger(terminal, 0)) {
            int awarded = LuckyPandaResultUtil.scatterFreeAwarded(terminal.scatterTokens());
            if (awarded > maxMarySpins) {
                throw new RoundRejectedException("scatter-free awards more than max-mary-spins");
            }
            List<List<CompleteRoundFact.PageFact>> spins = new ArrayList<>(awarded);
            for (int i = 0; i < awarded; i++) {
                LuckyPandaBoard freeStart;
                try {
                    freeStart = boards.generate(WeightScene.FREE_START, GameRuleCore.SCAT_TOTAL_MAX_BLOCKS, true);
                    if (!GameRuleCore.withinCapturedCaps(freeStart)) {
                        throw new RoundRejectedException("free start exceeded captured Scat/Wild caps");
                    }
                    Segment free = tumble(boards, random, rpx, i + 1, freeStart,
                            WeightScene.FREE_CASCADE_REFILL, maxConsecutiveWins,
                            GameRuleCore.SCAT_TOTAL_MAX_BLOCKS);
                    spins.add(free.pages());
                    if (LuckyPandaResultUtil.scatterRetrigger(free.terminalEvaluation())) {
                        int extra = LuckyPandaResultUtil.scatterFreeAwarded(
                                free.terminalEvaluation().scatterTokens());
                        if (awarded + extra > maxMarySpins) {
                            throw new RoundRejectedException("scatter retrigger exceeds max-mary-spins");
                        }
                        awarded += extra;
                    }
                } catch (IllegalStateException rejected) {
                    throw new RoundRejectedException(rejected.getMessage());
                }
            }
            freeSpins = List.copyOf(spins);
        }
        CompleteRoundFact fact = new CompleteRoundFact(betSize, betLevel, paid.pages(), freeSpins);
        RoundVerification verification = new CompleteRoundCodec().verify(
                fact, maxConsecutiveWins);
        return new GeneratedRound(fact, verification.terminalRwa(), verification.actualMultiplier());
    }

    private Segment tumble(LuckyPandaBoardGenerator boards, Random random, LuckyPandaRpxTracker rpx,
                           int nfsc, LuckyPandaBoard start, WeightScene refill, int maxConsecutiveWins,
                           int maxScatterTokens) {
        List<CompleteRoundFact.PageFact> pages = new ArrayList<>();
        LuckyPandaBoard board = start;
        LuckyPandaFrameAssigner.Frames frames = LuckyPandaFrameAssigner.assign(board, random);
        int wins = 0;
        LuckyPandaEvaluation evaluation;
        while (true) {
            if (!GameRuleCore.withinCapturedCaps(board) || board.scatterTokens() > maxScatterTokens) {
                throw new RoundRejectedException("page exceeded captured symbol caps");
            }
            int pageRpx = rpx.next(board, nfsc);
            evaluation = LuckyPandaResultUtil.evaluate(board, betSize, betLevel, pageRpx);
            pages.add(page(board, pageRpx, frames));
            if (!evaluation.hasWaysWin()) break;
            wins++;
            if (wins >= maxConsecutiveWins) {
                LuckyPandaBoardGenerator.CascadeResult forced = boards.cascade(
                        board, evaluation, refill, maxScatterTokens, true, frames.gfl(), frames.sfl());
                board = forced.board();
                frames = forced.frames();
                int terminalRpx = rpx.next(board, nfsc);
                LuckyPandaEvaluation next = LuckyPandaResultUtil.evaluate(board, betSize, betLevel, terminalRpx);
                if (next.hasWaysWin()) {
                    throw new RoundRejectedException("natural cascade still wins at max-consecutive-wins");
                }
                pages.add(page(board, terminalRpx, frames));
                evaluation = next;
                break;
            }
            LuckyPandaBoardGenerator.CascadeResult tumbled = boards.cascade(
                    board, evaluation, refill, maxScatterTokens, true, frames.gfl(), frames.sfl());
            board = tumbled.board();
            frames = tumbled.frames();
        }
        return new Segment(List.copyOf(pages), evaluation);
    }

    private static CompleteRoundFact.PageFact page(LuckyPandaBoard board, int rpx,
                                                   LuckyPandaFrameAssigner.Frames frames) {
        return new CompleteRoundFact.PageFact(board, rpx, frames.gfl(), frames.sfl());
    }

    private record Segment(List<CompleteRoundFact.PageFact> pages, LuckyPandaEvaluation terminalEvaluation) { }
}
