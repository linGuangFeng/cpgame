package com.cpgame.batchc.cybergo;

import static com.cpgame.batchc.cybergo.CyberGoModels.*;
import static com.cpgame.batchc.cybergo.CyberGoRules.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** 从付费起点一次性建立到合法终态的完整Round。 */
public final class RoundFactory {
    private static final int MAX_CANDIDATE_ATTEMPTS = 10_000;
    private final RandomGenerator random;
    private final RandomCandidateGenerator candidates;
    private final CompleteRoundVerifier verifier;
    private final GenerationLimits limits;

    public RoundFactory(RandomGenerator random, GenerationLimits limits) {
        this.random = Objects.requireNonNull(random);
        this.limits = Objects.requireNonNull(limits);
        this.candidates = new RandomCandidateGenerator(random, limits.symbolWeights());
        this.verifier = new CompleteRoundVerifier(limits);
        if (LossDefaults.BOARDS.size() != 10) throw new IllegalStateException("loss defaults");
    }

    /** 不预选模式：先产生自然候选，再由ResultUtil反推真实类型。 */
    public CompleteRound createRandomCompleteRound() {
        for (int attempt = 0; attempt < MAX_CANDIDATE_ATTEMPTS; attempt++) {
            List<String> paidBoard = candidates.paidBoardCandidate();
            ResultUtil.Evaluation evaluation = RuleEvaluator.evaluate(paidBoard, MINIMUM_BET_LEVEL, MINIMUM_BET_SIZE);
            if (evaluation.scatterCount() > 5) continue;
            CompleteRound round = evaluation.scatterCount() >= 3
                    ? buildFreeRound(paidBoard, evaluation, null)
                    : buildOrdinaryRound(paidBoard, evaluation);
            if (withinLimits(round)) return round;
        }
        throw new IllegalStateException("无法在安全重试上限内生成完整Round");
    }

    public CompleteRound createOrdinaryLoss() { return generateWithCandidates(candidates::independentLossCandidate); }
    CompleteRound generateWithCandidates(java.util.function.Supplier<List<String>> proposals) {
        for (int attempt=0; attempt<5; attempt++) {
            List<String> board = proposals.get();
            if(board==null)continue;
            ResultUtil.Evaluation evaluation = RuleEvaluator.evaluate(board, MINIMUM_BET_LEVEL, MINIMUM_BET_SIZE);
            if (evaluation.isLoss()) {
                CompleteRound round = buildOrdinaryRound(board, evaluation);
                verifier.verify(round);
                return round;
            }
        }
        List<String> board = LossDefaults.BOARDS.get(random.nextInt(10));
        CompleteRound round = buildOrdinaryRound(board, RuleEvaluator.evaluate(board, MINIMUM_BET_LEVEL, MINIMUM_BET_SIZE));
        verifier.verify(round);
        return round;
    }
    private static final class LossDefaults {
        static final List<List<String>> BOARDS = create();
        private static List<List<String>> create() {
            var generator = new RandomCandidateGenerator(new java.security.SecureRandom());
            List<List<String>> defaults = new ArrayList<>(10);
            for (int i=0;i<10;i++) {
                List<String> b=generator.independentLossCandidate();
                if (!RuleEvaluator.evaluate(b, MINIMUM_BET_LEVEL, MINIMUM_BET_SIZE).isLoss())
                    throw new ExceptionInInitializerError("invalid default loss");
                defaults.add(b);
            }
            return List.copyOf(defaults);
        }
    }

    public CompleteRound createOrdinaryWin() {
        for (int attempt = 0; attempt < MAX_CANDIDATE_ATTEMPTS; attempt++) {
            List<String> board = candidates.paidBoardCandidate();
            ResultUtil.Evaluation evaluation = RuleEvaluator.evaluate(board, MINIMUM_BET_LEVEL, MINIMUM_BET_SIZE);
            if (evaluation.baseWin().signum() > 0 && evaluation.scatterCount() < 3) {
                CompleteRound round = buildOrdinaryRound(board, evaluation);
                if (withinLimits(round)) return round;
            }
        }
        throw new IllegalStateException("无法在安全重试上限内生成普通WIN");
    }

    public CompleteRound createFreeSpinRound() {
        for (int attempt = 0; attempt < MAX_CANDIDATE_ATTEMPTS; attempt++) {
            List<String> board = candidates.paidBoardCandidate();
            ResultUtil.Evaluation evaluation = RuleEvaluator.evaluate(board, MINIMUM_BET_LEVEL, MINIMUM_BET_SIZE);
            if (evaluation.scatterCount() < 3 || evaluation.scatterCount() > 5) continue;
            CompleteRound round = buildFreeRound(board, evaluation, null);
            if (withinLimits(round)) return round;
        }
        throw new IllegalStateException("无法在安全重试上限内生成免费完整Round");
    }

    /** 从最小盘面事实重建全部派生字段；不读取任何历史响应壳。 */
    public CompleteRound rebuild(MinimalRoundFacts facts) {
        List<String> paidBoard = facts.boards().getFirst();
        ResultUtil.Evaluation paid = RuleEvaluator.evaluate(paidBoard, MINIMUM_BET_LEVEL, MINIMUM_BET_SIZE);
        CompleteRound round;
        if (paid.scatterCount() >= 3 && paid.scatterCount() <= 5) {
            int expected = FREE_SPIN_AWARDS.get(paid.scatterCount()) + 1;
            if (facts.boards().size() != expected) throw new IllegalArgumentException("最小事实的免费盘面数量非法");
            round = buildFreeRound(paidBoard, paid, facts.boards().subList(1, facts.boards().size()));
        } else if (paid.scatterCount() < 3) {
            if (facts.boards().size() != 1) throw new IllegalArgumentException("普通局最小事实只能包含一个盘面");
            round = buildOrdinaryRound(paidBoard, paid);
        } else {
            throw new IllegalArgumentException("Scatter数量超出能力清单");
        }
        verifier.verify(round);
        return round;
    }

    private CompleteRound buildOrdinaryRound(List<String> board, ResultUtil.Evaluation evaluation) {
        String roundKey = newRoundKey();
        long now = Instant.now().getEpochSecond();
        RoundKind kind = evaluation.baseWin().signum() > 0 ? RoundKind.ORDINARY_WIN : RoundKind.ORDINARY_LOSS;
        Step first = step(roundKey, 0, board, evaluation, MINIMUM_BET, 0, 0, 1, 1,
                evaluation.baseWin(), 0, 1, now);
        return new CompleteRound(roundKey, kind, MINIMUM_BET, now, List.of(first));
    }

    private CompleteRound buildFreeRound(List<String> paidBoard, ResultUtil.Evaluation paid,
                                         List<List<String>> suppliedFreeBoards) {
        int freeSpins = FREE_SPIN_AWARDS.get(paid.scatterCount());
        String roundKey = newRoundKey();
        long now = Instant.now().getEpochSecond();
        List<Step> steps = new ArrayList<>(freeSpins + 1);
        BigDecimal cumulative = paid.baseWin().setScale(2);
        steps.add(step(roundKey, 0, paidBoard, paid, MINIMUM_BET, freeSpins, 0, 1, 1,
                cumulative, 0, 1, now));

        int multiplier = 2;
        int collectedWilds = 0;
        for (int index = 1; index <= freeSpins; index++) {
            List<String> board = suppliedFreeBoards == null
                    ? candidates.freeBoardCandidate() : suppliedFreeBoards.get(index - 1);
            ResultUtil.Evaluation evaluation = RuleEvaluator.evaluate(board, MINIMUM_BET_LEVEL, MINIMUM_BET_SIZE);
            if (evaluation.scatterCount() != 0) throw new IllegalArgumentException("免费盘面不得出现Scatter");
            collectedWilds += evaluation.wildCount();
            while (collectedWilds >= 3 && multiplier < 20) {
                multiplier = Math.min(20, multiplier + 2);
                collectedWilds -= 3;
            }
            BigDecimal win = evaluation.baseWin().multiply(BigDecimal.valueOf(multiplier)).setScale(2, RoundingMode.HALF_UP);
            cumulative = cumulative.add(win).setScale(2);
            steps.add(step(roundKey, index, board, evaluation, BigDecimal.ZERO.setScale(2), freeSpins,
                    index, multiplier, 2, cumulative, 2, index == freeSpins ? 0 : 1, now));
        }
        return new CompleteRound(roundKey, RoundKind.FREE_SPINS, MINIMUM_BET, now, steps);
    }

    private Step step(String roundKey, int index, List<String> board, ResultUtil.Evaluation evaluation,
                      BigDecimal ba, int fsn, int nfsc, int multiplier, int gt, BigDecimal cumulative,
                      int smallGameType, int ss, long timestamp) {
        BigDecimal win = evaluation.baseWin().multiply(BigDecimal.valueOf(multiplier)).setScale(2, RoundingMode.HALF_UP);
        return new Step(ba.setScale(2), GAME_ID + "-" + roundKey + (index == 0 ? "" : "-" + index),
                MINIMUM_BET_LEVEL, MINIMUM_BET_SIZE, timestamp, fsn, BigDecimal.ZERO.setScale(2), gt,
                nfsc, multiplier, List.copyOf(board), cumulative.setScale(2), smallGameType, ss, win,
                evaluation.matches(), evaluation.winningSymbols());
    }

    private boolean withinLimits(CompleteRound round) {
        try {
            verifier.verify(round);
            return true;
        } catch (CompleteRoundVerifier.RoundLimitExceededException ignored) {
            return false;
        }
    }

    private String newRoundKey() {
        return Long.toUnsignedString(Instant.now().toEpochMilli()) + Long.toUnsignedString(random.nextLong(), 36);
    }
}
