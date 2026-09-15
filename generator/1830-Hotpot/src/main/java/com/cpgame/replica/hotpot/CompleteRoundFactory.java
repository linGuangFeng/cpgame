package com.cpgame.replica.hotpot;

import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotBoard;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotBoardGenerator;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotEvaluation;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotIndependentLossGenerator;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotPageKind;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotResultUtil;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotRoundKind;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotSpinMode;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotSymbolScene;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotGameRuleCore;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Generates one complete paid Round. Random construction and ResultUtil evaluation stay separate. */
public final class CompleteRoundFactory {
    private static final int DEFAULT_MAX_FREE_SPINS = 30;
    private final HotpotGameRuleCore core = new HotpotGameRuleCore();

    public GeneratedRound generateIndependentLoss(Random random) {
        HotpotBoard board = new HotpotIndependentLossGenerator().generate(random);
        CompleteRoundFact fact = new CompleteRoundFact(CompleteRoundFact.VERSION,
                List.of(List.of(CompleteRoundFact.fromBoard(board))));
        RoundVerification verification = new CompleteRoundCodec().verify(
                fact, 10, DEFAULT_MAX_FREE_SPINS);
        if (verification.multiplier() != 0 || verification.scatterFreeSpins()) {
            throw new IllegalStateException("independent loss failed verification");
        }
        return new GeneratedRound(fact, 0, 0, HotpotRoundKind.ORDINARY_LOSS);
    }

    public GeneratedRound generate(Random random, int maxConsecutiveWins, int maxFreeSpins) {
        return generate(random, maxConsecutiveWins, maxFreeSpins,
                HotpotBoardGenerator.defaultPaidStartWeights(),
                HotpotBoardGenerator.defaultCascadeWeights(),
                HotpotBoardGenerator.defaultFreeStartWeights());
    }

    public GeneratedRound generate(Random random, int maxConsecutiveWins, int maxFreeSpins,
                                   int[] paidStartWeights, int[] cascadeWeights, int[] freeStartWeights) {
        if (maxConsecutiveWins < 1) throw new IllegalArgumentException("max-consecutive-wins must be >= 1");
        if (maxFreeSpins < 1) throw new IllegalArgumentException("max-free-spins must be >= 1");
        if (random == null) throw new IllegalArgumentException("random source is required");
        HotpotBoardGenerator boards = new HotpotBoardGenerator(random, paidStartWeights, cascadeWeights, freeStartWeights);
        List<List<CompleteRoundFact.BoardFact>> spins = new ArrayList<>();

        SpinResult paid = generateSpin(boards, boards.generate(HotpotSymbolScene.PAID_START),
                HotpotSpinMode.PAID, maxConsecutiveWins);
        spins.add(paid.pages());
        int total = paid.multiplier();
        int maxObserved = paid.consecutiveWins();
        int freeTotal = paid.awardedFreeSpins();
        if (freeTotal > maxFreeSpins) {
            throw new RoundRejectedException("initial free spins exceed max-free-spins");
        }
        int freeIndex = 0;
        while (freeIndex < freeTotal) {
            SpinResult free = generateSpin(boards, boards.generate(HotpotSymbolScene.FREE_START),
                    HotpotSpinMode.FREE, maxConsecutiveWins);
            spins.add(free.pages());
            total = Math.addExact(total, free.multiplier());
            maxObserved = Math.max(maxObserved, free.consecutiveWins());
            int extended = freeTotal + free.awardedFreeSpins();
            if (extended > maxFreeSpins) {
                throw new RoundRejectedException("extended free spins exceed max-free-spins");
            }
            freeTotal = extended;
            freeIndex++;
        }
        CompleteRoundFact fact = new CompleteRoundFact(CompleteRoundFact.VERSION, spins);
        RoundVerification verification = verifyGenerated(fact, maxConsecutiveWins, maxFreeSpins);
        if (verification.multiplier() != total) {
            throw new RoundRejectedException("independent round verification mismatch");
        }
        boolean scatter = freeTotal > 0;
        HotpotRoundKind kind = core.classifyRound(scatter, total);
        return new GeneratedRound(fact, total, maxObserved, kind);
    }

    static RoundVerification verifyGenerated(CompleteRoundFact fact, int maxConsecutiveWins, int maxFreeSpins) {
        try {
            return new CompleteRoundCodec().verify(fact, maxConsecutiveWins, maxFreeSpins);
        } catch (CompleteRoundCodec.CandidateLimitException overLimit) {
            // The loader retries this candidate; configuration and codec defects still fail visibly.
            throw new RoundRejectedException(overLimit.getMessage());
        }
    }

    private SpinResult generateSpin(HotpotBoardGenerator boards, HotpotBoard board, HotpotSpinMode mode,
                                    int maxConsecutiveWins) {
        List<CompleteRoundFact.BoardFact> pages = new ArrayList<>();
        int wins = 0;
        HotpotSymbolScene fillScene = fillScene(mode);
        while (true) {
            HotpotEvaluation evaluation = HotpotResultUtil.evaluate(board);
            pages.add(CompleteRoundFact.fromBoard(board));
            switch (evaluation.getPageKind()) {
                case TERMINAL_NO_WIN -> {
                    int awarded = HotpotResultUtil.awardedFreeSpins(evaluation.getScatterCount(), mode);
                    List<HotpotBoard> spinBoards = new ArrayList<>(pages.size());
                    for (CompleteRoundFact.BoardFact page : pages) spinBoards.add(page.toBoard());
                    int multiplier = HotpotResultUtil.spinIntegerMultiplier(spinBoards);
                    return new SpinResult(List.copyOf(pages), multiplier, awarded, wins);
                }
                case WIN -> {
                    wins++;
                    if (wins >= maxConsecutiveWins) {
                        board = terminalAfter(boards, board, evaluation, fillScene);
                    } else {
                        board = boards.cascade(board, evaluation, fillScene);
                    }
                }
            }
        }
    }

    private HotpotBoard terminalAfter(HotpotBoardGenerator boards, HotpotBoard winning,
                                      HotpotEvaluation evaluation, HotpotSymbolScene fillScene) {
        HotpotBoard candidate = boards.cascade(winning, evaluation, fillScene);
        HotpotEvaluation terminal = HotpotResultUtil.evaluate(candidate);
        if (terminal.getPageKind() == HotpotPageKind.TERMINAL_NO_WIN) return candidate;
        throw new RoundRejectedException("natural cascade still wins at max-consecutive-wins");
    }

    private static HotpotSymbolScene fillScene(HotpotSpinMode mode) {
        switch (mode) {
            case PAID -> { return HotpotSymbolScene.PAID_CASCADE; }
            case FREE -> { return HotpotSymbolScene.FREE_CASCADE; }
        }
        throw new IllegalStateException("unhandled spin mode: " + mode);
    }

    public record GeneratedRound(CompleteRoundFact fact, int multiplier, int maxConsecutiveWins,
                                 HotpotRoundKind kind) { }

    public static final class RoundRejectedException extends RuntimeException {
        public RoundRejectedException(String message) { super(message); }
    }

    private record SpinResult(List<CompleteRoundFact.BoardFact> pages, int multiplier,
                              int awardedFreeSpins, int consecutiveWins) { }
}
