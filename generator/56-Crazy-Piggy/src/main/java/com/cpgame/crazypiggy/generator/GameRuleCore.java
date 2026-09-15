package com.cpgame.crazypiggy.generator;

import com.cpgame.crazypiggy.generator.model.RoundCandidate;
import com.cpgame.crazypiggy.generator.model.RoundFacts;
import com.cpgame.crazypiggy.generator.model.RoundResult;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/** 试玩、正式 Loader 与 API 共用的唯一游戏规则核心。 */
public final class GameRuleCore {
    private final RandomCandidateGenerator candidateGenerator;
    private final RoundFactory roundFactory;
    private final RoundVerifier verifier;
    private final RandomGenerator random;
    private final LossGenerationPolicy lossPolicy;

    public GameRuleCore() {
        this(GenerationWeights.defaults(), LossGenerationPolicy.defaults());
    }

    public GameRuleCore(LossGenerationPolicy lossPolicy) {
        this(GenerationWeights.defaults(), lossPolicy);
    }

    public GameRuleCore(GenerationWeights weights) {
        this(weights, LossGenerationPolicy.defaults());
    }

    public GameRuleCore(GenerationWeights weights, LossGenerationPolicy lossPolicy) {
        this(new RandomCandidateGenerator(weights), new RoundFactory(), new RoundVerifier(), new SecureRandom(), lossPolicy);
    }

    private GameRuleCore(RandomCandidateGenerator candidateGenerator, RoundFactory roundFactory,
                         RoundVerifier verifier, RandomGenerator random, LossGenerationPolicy lossPolicy) {
        this.candidateGenerator = candidateGenerator;
        this.roundFactory = roundFactory;
        this.verifier = verifier;
        this.random = random;
        this.lossPolicy = lossPolicy;
    }

    /** 仅供显式测试入口复现；正式 Loader 与试玩均不接收 seed。 */
    public static GameRuleCore forTesting(long seed) {
        return forTesting(seed, GenerationWeights.defaults());
    }

    public static GameRuleCore forTesting(long seed, GenerationWeights weights) {
        return new GameRuleCore(new RandomCandidateGenerator(weights),
                new RoundFactory(RoundFactory.deterministicIdentitySource(seed ^ 0x56C0FFEE56L)),
                new RoundVerifier(), new SplittableRandom(seed), LossGenerationPolicy.defaults());
    }

    /** Controller 专用：只恢复与复核 Redis 完整局，不初始化生成模型或随机源。 */
    public static GameRuleCore forRestoration() {
        return new GameRuleCore(null, new RoundFactory(), new RoundVerifier(), null, null);
    }

    public RoundResult generatePaidRound(BigDecimal betSize, int betLevel) {
        requireGenerationEnabled();
        return finish(candidateGenerator.natural(random), betSize, betLevel);
    }

    public RoundResult generatePaidRound(BigDecimal betSize, int betLevel, RandomGenerator suppliedRandom) {
        requireGenerationEnabled();
        return finish(candidateGenerator.natural(suppliedRandom), betSize, betLevel);
    }

    /** 只可在没有 active Round 的普通付费空闲态使用。 */
    public RoundResult generateIndependentLoss(BigDecimal betSize, int betLevel) {
        requireGenerationEnabled();
        return finish(candidateGenerator.independentLoss(random, lossPolicy), betSize, betLevel);
    }

    public RoundResult generateIndependentLoss(BigDecimal betSize, int betLevel, RandomGenerator suppliedRandom) {
        requireGenerationEnabled();
        return finish(candidateGenerator.independentLoss(suppliedRandom, lossPolicy), betSize, betLevel);
    }

    public RoundResult generateOrdinaryWin(BigDecimal betSize, int betLevel, RandomGenerator suppliedRandom) {
        requireGenerationEnabled();
        return finish(candidateGenerator.ordinaryWin(suppliedRandom), betSize, betLevel);
    }

    public RoundResult generateOrdinaryWin(BigDecimal betSize, int betLevel) {
        requireGenerationEnabled();
        return finish(candidateGenerator.ordinaryWin(random), betSize, betLevel);
    }

    public RoundResult generateBoosterRound(BigDecimal betSize, int betLevel, RandomGenerator suppliedRandom) {
        requireGenerationEnabled();
        return finish(candidateGenerator.boosterWheel(suppliedRandom), betSize, betLevel);
    }

    public RoundResult generateBoosterRound(BigDecimal betSize, int betLevel) {
        requireGenerationEnabled();
        return finish(candidateGenerator.boosterWheel(random), betSize, betLevel);
    }

    public RoundResult restore(RoundFacts facts) {
        validateBet(facts.betSize(), facts.betLevel());
        RoundResult restored = roundFactory.restore(facts);
        verifier.verify(restored);
        return restored;
    }

    public double validateIndependentLossStrategy() {
        requireGenerationEnabled();
        double rate = candidateGenerator.measureFirstAttemptLossSuccess(random, lossPolicy.validationSamples());
        if (rate < lossPolicy.minimumFirstAttemptSuccessRate()) {
            throw new IllegalStateException("构造式 LOSS 首次成功率低于配置下限: " + rate);
        }
        return rate;
    }

    private RoundResult finish(RoundCandidate candidate, BigDecimal betSize, int betLevel) {
        validateBet(betSize, betLevel);
        RoundResult result = roundFactory.create(candidate, betSize, betLevel);
        verifier.verify(result);
        return result;
    }

    private void requireGenerationEnabled() {
        if (candidateGenerator == null || random == null)
            throw new IllegalStateException("restore-only GameRuleCore 禁止生成");
    }

    private static void validateBet(BigDecimal betSize, int betLevel) {
        boolean validSize = GameRules.BET_SIZES.stream().anyMatch(v -> v.compareTo(betSize) == 0);
        if (!validSize) throw new IllegalArgumentException("非法 bs: " + betSize);
        if (!GameRules.BET_LEVELS.contains(betLevel)) throw new IllegalArgumentException("非法 bl: " + betLevel);
    }
}
