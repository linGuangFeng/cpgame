package com.cpgame.curupira.core;

import com.cpgame.curupira.model.CompleteRoundFact;
import com.cpgame.curupira.model.CompleteRoundFact.EntryKind;
import com.cpgame.curupira.model.CompleteRoundFact.Kind;
import com.cpgame.curupira.model.EvaluatedBoard;
import com.cpgame.curupira.model.FeatureStep;
import com.cpgame.curupira.model.FeatureStep.Role;
import com.cpgame.curupira.random.RandomSource;
import com.cpgame.curupira.random.SecureRandomSource;
import com.cpgame.curupira.random.WeightedSymbolSampler;
import com.cpgame.curupira.verify.RoundVerifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Loader 与 Demo 共用的独立 Java 规则核心；每次调用只生成一个自然候选事实。 */
public final class GameRuleCore {
    private final CandidateBoardGenerator candidates;
    private final MaryRoundGenerator mary;
    private final ResultUtil resultUtil = new ResultUtil();
    private final RoundVerifier verifier = new RoundVerifier();
    private final RoundIdGenerator ids = new RoundIdGenerator();

    public GameRuleCore() {
        this(new SecureRandomSource(), GenerationPolicy.ordinaryPaidDefaults(), 9, 1);
    }

    public GameRuleCore(RandomSource random, GenerationPolicy policy) {
        this(random, policy, 9, 1);
    }

    public GameRuleCore(RandomSource random, GenerationPolicy policy, int holdEmptyWeight, int holdCoinWeight) {
        WeightedSymbolSampler sampler = new WeightedSymbolSampler(random, policy.symbolWeights());
        candidates = new CandidateBoardGenerator(sampler);
        mary = new MaryRoundGenerator(random, sampler, holdEmptyWeight, holdCoinWeight);
    }

    /** 不接受目标类别或目标奖金；先发牌，再由独立规则分类。 */
    public CompleteRoundFact generatePaidCandidate() {
        List<Integer> board = candidates.nextBoard();
        EvaluatedBoard evaluated = resultUtil.evaluate(board);
        Kind kind = resultUtil.classifyPaid(evaluated);
        Role role = kind == Kind.TRIGGER ? Role.TRIGGER : Role.ORDINARY;
        FeatureStep step = kind == Kind.TRIGGER
                ? FeatureStep.symbol(role, board, evaluated, 1, 1, 1, 1, 1)
                : FeatureStep.symbol(role, board, evaluated, 0, 0, 0, 1, 1);
        CompleteRoundFact fact = new CompleteRoundFact(ids.next(), kind, EntryKind.PAID, List.of(step));
        verifier.verifyFact(fact);
        return fact;
    }

    /** 免费扩展 Wild 是独立 Mary 场景选择，不是按奖金结果造局。 */
    public CompleteRoundFact generateFreeExpandingWildCandidate() {
        CompleteRoundFact fact = mary.freeExpandingWild();
        verifier.verifyFact(fact);
        return fact;
    }

    /** Hold & Spins 是独立 Mary 场景选择，不是按奖金结果造局。 */
    public CompleteRoundFact generateHoldAndSpinsCandidate() {
        CompleteRoundFact fact = mary.holdAndSpins();
        verifier.verifyFact(fact);
        return fact;
    }

    public static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public static final class InsufficientBalanceException extends RuntimeException {
        public InsufficientBalanceException() { super("Insufficient balance"); }
    }
}
