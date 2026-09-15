package com.cpgame.saci.generator;

import com.cpgame.saci.generator.model.RoundCandidate;
import com.cpgame.saci.generator.model.RoundFacts;
import com.cpgame.saci.generator.model.RoundMode;
import com.cpgame.saci.generator.model.RoundResult;
import com.cpgame.saci.generator.model.StepFact;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/** Loader 与 Controller 共用的唯一规则核心。Controller 只能 restore。 */
public final class GameRuleCore {
    private final RandomCandidateGenerator candidateGenerator;
    private final RoundFactory roundFactory;
    private final RoundVerifier verifier;
    private final RandomGenerator random;

    public GameRuleCore() {
        this(new RandomCandidateGenerator(), new RoundFactory(), new RoundVerifier(), new SecureRandom());
    }

    private GameRuleCore(RandomCandidateGenerator candidateGenerator, RoundFactory roundFactory,
                         RoundVerifier verifier, RandomGenerator random) {
        this.candidateGenerator = candidateGenerator;
        this.roundFactory = roundFactory;
        this.verifier = verifier;
        this.random = random;
    }

    public static GameRuleCore forTesting(long seed) {
        return new GameRuleCore(new RandomCandidateGenerator(),
                new RoundFactory(RoundFactory.deterministicIdentitySource(seed ^ 0x615AC111L)),
                new RoundVerifier(), new SplittableRandom(seed));
    }

    public static GameRuleCore forRestoration() {
        return new GameRuleCore(null, new RoundFactory(), new RoundVerifier(), null);
    }

    public RoundResult generateIndependentLoss(int bl, BigDecimal bs, BigDecimal startingBalance) {
        return generateIndependentLoss(bl, bs, startingBalance, random);
    }

    public RoundResult generateIndependentLoss(int bl, BigDecimal bs, BigDecimal startingBalance,
                                               RandomGenerator supplied) {
        requireGenerationEnabled();
        return finish(candidateGenerator.independentLoss(supplied), bl, bs, startingBalance);
    }

    public RoundResult generateOrdinaryWin(int bl, BigDecimal bs, BigDecimal startingBalance) {
        return generateOrdinaryWin(bl, bs, startingBalance, random);
    }

    public RoundResult generateOrdinaryWin(int bl, BigDecimal bs, BigDecimal startingBalance,
                                           RandomGenerator supplied) {
        requireGenerationEnabled();
        return finish(candidateGenerator.ordinaryWin(supplied), bl, bs, startingBalance);
    }

    public RoundResult generateSpecial(int bl, BigDecimal bs, BigDecimal startingBalance) {
        return generateSpecial(bl, bs, startingBalance, random);
    }

    public RoundResult generateSpecial(int bl, BigDecimal bs, BigDecimal startingBalance,
                                       RandomGenerator supplied) {
        requireGenerationEnabled();
        return finish(candidateGenerator.special(supplied), bl, bs, startingBalance);
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

    /**
     * 2290 同款：当前能量进 UTIL，普通事实与漩涡尾部分开取，触发则拼成一局。
     * restore-only Controller 也可以调用；禁止在这里抽 Redis 或现场出牌。
     */
    public RoundResult compose(RoundCandidate ordinary, ResultUtil.EnergyState energy,
                               RoundCandidate vortexSource, int bl, BigDecimal bs,
                               BigDecimal startingBalance) {
        if (ordinary == null) throw new IllegalArgumentException("ordinary candidate required");
        ResultUtil.EnergyProjection projection = ResultUtil.applyEnergyTransition(energy, ordinary.steps());
        List<StepFact> tail = List.of();
        if (projection.vortex()) {
            if (vortexSource == null || vortexSource.mode() != RoundMode.WILD_VORTEX) {
                throw new IllegalStateException("full energy requires vortex tail cache");
            }
            tail = ResultUtil.vortexTail(vortexSource.steps());
        }
        List<StepFact> stitched = ResultUtil.stitch(ordinary.steps(), projection, tail);
        RoundMode mode = projection.vortex() ? RoundMode.WILD_VORTEX : ordinary.mode();
        RoundResult result = roundFactory.create(new RoundCandidate(mode, stitched), bl, bs, startingBalance);
        verifier.verify(result);
        return result;
    }

    public int trainingKernelCount() {
        requireGenerationEnabled();
        return candidateGenerator.trainingKernelCount();
    }

    public double validateIndependentLossStrategy() {
        requireGenerationEnabled();
        double rate = candidateGenerator.measureFirstAttemptLossSuccess(random, 200);
        if (rate < 0.9d) throw new IllegalStateException("LOSS kernel 首次成功率低于下限: " + rate);
        return rate;
    }

    private RoundResult finish(RoundCandidate candidate, int bl, BigDecimal bs, BigDecimal startingBalance) {
        RoundResult result = roundFactory.create(candidate, bl, bs, startingBalance);
        verifier.verify(result);
        return result;
    }

    private void requireGenerationEnabled() {
        if (candidateGenerator == null || random == null) {
            throw new IllegalStateException("restore-only GameRuleCore 禁止生成");
        }
    }
}
