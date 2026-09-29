package com.cpgame.crazy777.generator;

import com.cpgame.crazy777.generator.model.RoundCandidate;
import com.cpgame.crazy777.generator.model.RoundFacts;
import com.cpgame.crazy777.generator.model.RoundResult;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/** 试玩、正式 Loader 与 API 共用的唯一游戏规则核心。 */
public final class GameRuleCore {
    private RandomCandidateGenerator candidateGenerator;
    private final RoundFactory roundFactory;
    private final RoundVerifier verifier;
    private final RandomGenerator random;
    private final IndependentLossGenerator losses = new IndependentLossGenerator();

    public GameRuleCore() {
        this(null, new RoundFactory(), new RoundVerifier(), new SecureRandom());
    }

    public GameRuleCore(SymbolWeights weights) {
        this(new RandomCandidateGenerator(weights), new RoundFactory(), new RoundVerifier(), new SecureRandom());
    }

    private GameRuleCore(RandomCandidateGenerator candidateGenerator, RoundFactory roundFactory,
                         RoundVerifier verifier, RandomGenerator random) {
        this.candidateGenerator = candidateGenerator;
        this.roundFactory = roundFactory;
        this.verifier = verifier;
        this.random = random;
    }

    /** 仅供显式测试入口复现；正式 Loader 与试玩均不接收 seed。 */
    public static GameRuleCore forTesting(long seed) {
        return new GameRuleCore(new RandomCandidateGenerator(),
                new RoundFactory(RoundFactory.deterministicIdentitySource(seed ^ 0x57C0FFEE57L)),
                new RoundVerifier(), new SplittableRandom(seed));
    }

    /** Controller 专用：只恢复与复核 Redis 完整局，不初始化生成模型或随机源。 */
    public static GameRuleCore forRestoration() {
        return new GameRuleCore(null, new RoundFactory(), new RoundVerifier(), null);
    }

    public RoundResult generatePaidRound(int bl, BigDecimal bs, BigDecimal startingBalance) {
        return finish(gen().natural(random), bl, bs, startingBalance);
    }

    public RoundResult generateIndependentLoss(int bl, BigDecimal bs, BigDecimal startingBalance) {
        requireRandom();
        return finish(losses.generate(random), bl, bs, startingBalance);
    }

    public RoundResult generateIndependentLoss(int bl, BigDecimal bs, BigDecimal startingBalance,
                                               RandomGenerator supplied) {
        requireRandom();
        return finish(losses.generate(supplied), bl, bs, startingBalance);
    }

    public RoundResult generateOrdinaryWin(int bl, BigDecimal bs, BigDecimal startingBalance) {
        return finish(gen().ordinaryWin(random), bl, bs, startingBalance);
    }

    public RoundResult generateOrdinaryWin(int bl, BigDecimal bs, BigDecimal startingBalance,
                                           RandomGenerator supplied) {
        return finish(gen().ordinaryWin(supplied), bl, bs, startingBalance);
    }

    public RoundResult generateFreeSpins(int bl, BigDecimal bs, BigDecimal startingBalance) {
        return finish(gen().freeSpins(random), bl, bs, startingBalance);
    }

    public RoundResult generateFreeSpins(int bl, BigDecimal bs, BigDecimal startingBalance,
                                         RandomGenerator supplied) {
        return finish(gen().freeSpins(supplied), bl, bs, startingBalance);
    }

    public RoundResult restore(RoundFacts facts) {
        RoundResult restored = roundFactory.restore(facts);
        verifier.verify(restored);
        return restored;
    }

    public RoundResult restore(RoundFacts facts, BigDecimal startingBalance) {
        RoundResult restored = roundFactory.restore(facts, startingBalance);
        verifier.verify(restored);
        return restored;
    }

    public double validateIndependentLossStrategy() {
        requireRandom();
        double rate = losses.measureFirstAttemptLossSuccess(random, 1000);
        if (rate < 0.9d) throw new IllegalStateException("构造式 LOSS 首次成功率低于配置下限: " + rate);
        return rate;
    }

    public int trainingKernelCount() {
        return gen().trainingKernelCount();
    }

    private RoundResult finish(RoundCandidate candidate, int bl, BigDecimal bs, BigDecimal startingBalance) {
        RoundResult result = roundFactory.create(candidate, bl, bs, startingBalance);
        verifier.verify(result);
        return result;
    }

    private void requireRandom() {
        if (random == null) throw new IllegalStateException("restore-only GameRuleCore 禁止生成");
    }

    private RandomCandidateGenerator gen() {
        requireRandom();
        if (candidateGenerator == null) candidateGenerator = new RandomCandidateGenerator();
        return candidateGenerator;
    }
}
