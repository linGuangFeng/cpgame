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
    private RandomCandidateGenerator candidateGenerator;
    private final GenerationWeights pendingWeights;
    private final RoundFactory roundFactory;
    private final RoundVerifier verifier;
    private final RandomGenerator random;
    private final LossGenerationPolicy lossPolicy;
    private final IndependentLossGenerator losses = new IndependentLossGenerator();

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
        this(null, weights, new RoundFactory(), new RoundVerifier(), new SecureRandom(), lossPolicy);
    }

    private GameRuleCore(RandomCandidateGenerator candidateGenerator, GenerationWeights pendingWeights,
                         RoundFactory roundFactory, RoundVerifier verifier, RandomGenerator random,
                         LossGenerationPolicy lossPolicy) {
        this.candidateGenerator = candidateGenerator;
        this.pendingWeights = pendingWeights;
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
        return new GameRuleCore(new RandomCandidateGenerator(weights), weights,
                new RoundFactory(RoundFactory.deterministicIdentitySource(seed ^ 0x56C0FFEE56L)),
                new RoundVerifier(), new SplittableRandom(seed), LossGenerationPolicy.defaults());
    }

    /** Controller 专用：只恢复与复核 Redis 完整局，不初始化生成模型或随机源。 */
    public static GameRuleCore forRestoration() {
        return new GameRuleCore(null, null, new RoundFactory(), new RoundVerifier(), null, null);
    }

    public RoundResult generatePaidRound(BigDecimal betSize, int betLevel) {
        return finish(gen().natural(random), betSize, betLevel);
    }

    public RoundResult generatePaidRound(BigDecimal betSize, int betLevel, RandomGenerator suppliedRandom) {
        return finish(gen().natural(suppliedRandom), betSize, betLevel);
    }

    /** 只可在没有 active Round 的普通付费空闲态使用。 */
    public RoundResult generateIndependentLoss(BigDecimal betSize, int betLevel) {
        requireRandom();
        return finish(losses.generate(random), betSize, betLevel);
    }

    public RoundResult generateIndependentLoss(BigDecimal betSize, int betLevel, RandomGenerator suppliedRandom) {
        requireRandom();
        return finish(losses.generate(suppliedRandom), betSize, betLevel);
    }

    public RoundResult generateOrdinaryWin(BigDecimal betSize, int betLevel, RandomGenerator suppliedRandom) {
        return finish(gen().ordinaryWin(suppliedRandom), betSize, betLevel);
    }

    public RoundResult generateOrdinaryWin(BigDecimal betSize, int betLevel) {
        return finish(gen().ordinaryWin(random), betSize, betLevel);
    }

    public RoundResult generateBoosterRound(BigDecimal betSize, int betLevel, RandomGenerator suppliedRandom) {
        return finish(gen().boosterWheel(suppliedRandom), betSize, betLevel);
    }

    public RoundResult generateBoosterRound(BigDecimal betSize, int betLevel) {
        return finish(gen().boosterWheel(random), betSize, betLevel);
    }

    public RoundResult restore(RoundFacts facts) {
        RoundResult restored = roundFactory.restore(facts);
        verifier.verify(restored);
        return restored;
    }

    public double validateIndependentLossStrategy() {
        requireRandom();
        int samples = lossPolicy == null ? 1000 : lossPolicy.validationSamples();
        double min = lossPolicy == null ? 0.9d : lossPolicy.minimumFirstAttemptSuccessRate();
        double rate = losses.measureFirstAttemptLossSuccess(random, samples);
        if (rate < min) {
            throw new IllegalStateException("构造式 LOSS 首次成功率低于配置下限: " + rate);
        }
        return rate;
    }

    private RoundResult finish(RoundCandidate candidate, BigDecimal betSize, int betLevel) {
        RoundResult result = roundFactory.create(candidate, betSize, betLevel);
        verifier.verify(result);
        return result;
    }

    private void requireRandom() {
        if (random == null) throw new IllegalStateException("restore-only GameRuleCore 禁止生成");
    }

    private RandomCandidateGenerator gen() {
        requireRandom();
        if (candidateGenerator == null) {
            candidateGenerator = new RandomCandidateGenerator(
                    pendingWeights == null ? GenerationWeights.defaults() : pendingWeights);
        }
        return candidateGenerator;
    }

}
