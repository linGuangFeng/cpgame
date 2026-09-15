package com.cpgame.replica.freedomday;

import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayBoard;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayBoardGenerator;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayEvaluation;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayResultUtil;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayOrdinaryLossPolicy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Generates one complete paid Round. Random board generation and deterministic evaluation stay separate. */
public final class CompleteRoundFactory {
    private static final int DEFAULT_MAX_MARY_SPINS = 30;

    public GeneratedRound generate(Random random, boolean featureBuy, int maxConsecutiveWins) {
        return generate(random, featureBuy, maxConsecutiveWins, DEFAULT_MAX_MARY_SPINS);
    }

    public GeneratedRound generate(Random random, boolean featureBuy, int maxConsecutiveWins, int maxMarySpins) {
        return generate(random, featureBuy, maxConsecutiveWins, maxMarySpins,
                FreedomDayBoardGenerator.defaultNormalWeights(), FreedomDayBoardGenerator.defaultFreeWeights());
    }

    public GeneratedRound generate(Random random, boolean featureBuy, int maxConsecutiveWins, int maxMarySpins,
                                   int[] normalWeights, int[] maryWeights) {
        return generate(random, featureBuy, maxConsecutiveWins, maxMarySpins, normalWeights, maryWeights, true, false);
    }

    /** 倒数第二个参数仅供 10 万样本回归重建修改前基线；正式入口始终传 true。 */
    public GeneratedRound generate(Random random, boolean featureBuy, int maxConsecutiveWins, int maxMarySpins,
                                   int[] normalWeights, int[] maryWeights,
                                   boolean halveOrdinaryLossBallOccurrence) {
        return generate(random, featureBuy, maxConsecutiveWins, maxMarySpins, normalWeights, maryWeights,
                halveOrdinaryLossBallOccurrence, false);
    }

    public GeneratedRound generate(Random random, boolean featureBuy, int maxConsecutiveWins, int maxMarySpins,
                                   int[] normalWeights, int[] maryWeights,
                                   boolean halveOrdinaryLossBallOccurrence, boolean specialOpening) {
        if (maxConsecutiveWins < 1) throw new IllegalArgumentException("max-consecutive-wins must be >= 1");
        if (maxMarySpins < 1) throw new IllegalArgumentException("max-mary-spins must be >= 1");
        if (random == null) throw new IllegalArgumentException("random source is required");
        FreedomDayBoardGenerator boards = new FreedomDayBoardGenerator(random, normalWeights, maryWeights);
        List<List<CompleteRoundFact.BoardFact>> spins = new ArrayList<>();

        SpinResult paid = generateSpin(boards, featureBuy ? boards.generateFeatureTrigger()
                        : boards.generate(false, specialOpening),
                false, 1, 2, maxConsecutiveWins);
        if (!featureBuy && paid.multiplier().signum() == 0 && paid.pages().size() == 1
                && paid.awardedFreeSpins() == 0
                && halveOrdinaryLossBallOccurrence) {
            CompleteRoundFact.BoardFact only = paid.pages().get(0);
            FreedomDayBoard baselineLoss = new FreedomDayBoard(toArray(only.prop()), toArray(only.trl()),
                    only.grids(), only.gf(), only.sl());
            FreedomDayBoard adjusted = FreedomDayOrdinaryLossPolicy.halveBallOccurrence(
                    baselineLoss, random, normalWeights, maryWeights);
            paid = new SpinResult(List.of(toFact(adjusted)), BigDecimal.ZERO, 0, 1, 0);
        }
        spins.add(paid.pages());
        BigDecimal total = paid.multiplier();
        int maxObserved = paid.consecutiveWins();
        int freeTotal = paid.awardedFreeSpins();
        if (freeTotal > maxMarySpins) {
            throw new RoundRejectedException("initial Mary spins exceed max-mary-spins");
        }
        int freeIndex = 0;
        int freeMultiplier = 2;
        while (freeIndex < freeTotal) {
            SpinResult free = generateSpin(boards, boards.generate(true), true, freeMultiplier, 2,
                    maxConsecutiveWins);
            spins.add(free.pages());
            total = total.add(free.multiplier());
            maxObserved = Math.max(maxObserved, free.consecutiveWins());
            freeMultiplier = free.endingMultiplier();
            int extendedTotal = freeTotal + free.awardedFreeSpins();
            if (extendedTotal > maxMarySpins) {
                throw new RoundRejectedException("extended Mary spins exceed max-mary-spins");
            }
            freeTotal = extendedTotal;
            freeIndex++;
        }
        CompleteRoundFact fact = new CompleteRoundFact(CompleteRoundFact.VERSION, featureBuy, spins);
        // 运行时、Controller 与 Redis 共享同一道逐格门禁；候选失败时整局丢弃。
        RoundVerification verification = new CompleteRoundCodec().verify(fact, maxConsecutiveWins, maxMarySpins);
        if (verification.multiplier().compareTo(total.stripTrailingZeros()) != 0) {
            throw new RoundRejectedException("independent round verification mismatch");
        }
        return new GeneratedRound(fact, total.stripTrailingZeros(), maxObserved);
    }

    private SpinResult generateSpin(FreedomDayBoardGenerator boards, FreedomDayBoard board, boolean freeMode,
                                    int startingMultiplier, int increment, int maxConsecutiveWins) {
        List<CompleteRoundFact.BoardFact> pages = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int multiplier = startingMultiplier;
        int awarded = 0;
        int wins = 0;
        while (true) {
            FreedomDayEvaluation evaluation = FreedomDayResultUtil.evaluate(board, BigDecimal.ONE, multiplier, increment);
            pages.add(toFact(board));
            total = total.add(evaluation.getTotalMultiplier());
            multiplier = evaluation.getMultiplier();
            if (pages.size() == 1) awarded = evaluation.getAwardedFreeSpins();
            if (evaluation.getWins().isEmpty()) break;
            wins++;
            if (wins >= maxConsecutiveWins) {
                board = terminalAfter(boards, board, evaluation, freeMode, multiplier, increment);
            } else {
                board = boards.cascade(board, evaluation, freeMode);
            }
        }
        return new SpinResult(List.copyOf(pages), total, awarded, multiplier, wins);
    }

    private FreedomDayBoard terminalAfter(FreedomDayBoardGenerator boards, FreedomDayBoard winning,
                                          FreedomDayEvaluation evaluation, boolean freeMode,
                                          int multiplier, int increment) {
        FreedomDayBoard candidate = boards.cascade(winning, evaluation, freeMode);
        FreedomDayEvaluation terminal = FreedomDayResultUtil.evaluate(candidate, BigDecimal.ONE, multiplier, increment);
        if (terminal.getWins().isEmpty()) return candidate;
        throw new RoundRejectedException("natural cascade still wins at max-consecutive-wins");
    }

    private CompleteRoundFact.BoardFact toFact(FreedomDayBoard board) {
        return new CompleteRoundFact.BoardFact(toList(board.getProp()), toList(board.getTrl()),
                board.getGrids(), board.getGoldFrames(), board.getSilverFrames());
    }

    private List<Integer> toList(int[] values) {
        List<Integer> result = new ArrayList<>(values.length);
        for (int value : values) result.add(value);
        return result;
    }

    private int[] toArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
        return result;
    }

    public record GeneratedRound(CompleteRoundFact fact, BigDecimal multiplier, int maxConsecutiveWins) { }
    /** Normal candidate rejection; callers should discard the whole Round and generate another one. */
    public static final class RoundRejectedException extends RuntimeException {
        public RoundRejectedException(String message) { super(message); }
    }
    private record SpinResult(List<CompleteRoundFact.BoardFact> pages, BigDecimal multiplier,
                              int awardedFreeSpins, int endingMultiplier, int consecutiveWins) { }
}
