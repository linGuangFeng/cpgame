package com.cpgame.luckycatii;

import com.cpgame.luckycatii.model.RoundCandidate;
import com.cpgame.luckycatii.model.RoundFacts;
import com.cpgame.luckycatii.model.RoundResult;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/** Single rule core shared by the generator, Redis loader and controller restore path. */
public final class GameRuleCore {
    private final RandomCandidateGenerator candidates;
    private final RoundFactory roundFactory;
    private final RoundVerifier verifier;
    private final RandomGenerator random;

    public GameRuleCore() {
        this(new RandomCandidateGenerator(), new RoundFactory(), new RoundVerifier(), new SecureRandom());
    }

    private GameRuleCore(RandomCandidateGenerator candidates, RoundFactory roundFactory,
                         RoundVerifier verifier, RandomGenerator random) {
        this.candidates = candidates;
        this.roundFactory = roundFactory;
        this.verifier = verifier;
        this.random = random;
    }

    public static GameRuleCore forTesting(long seed) {
        return new GameRuleCore(new RandomCandidateGenerator(),
                new RoundFactory(RoundFactory.deterministicIdentitySource(seed ^ 0x50C47L)),
                new RoundVerifier(), new SplittableRandom(seed));
    }

    public static GameRuleCore forRestoration() {
        return new GameRuleCore(null, new RoundFactory(), new RoundVerifier(), null);
    }

    public RoundResult generateIndependentLoss(BigDecimal betSize, int betLevel) {
        return finish(requireGen().independentLoss(random), betSize, betLevel);
    }

    public RoundResult generateIndependentLoss(BigDecimal betSize, int betLevel, RandomGenerator rng) {
        return finish(requireGen().independentLoss(rng), betSize, betLevel);
    }

    public RoundResult generateOrdinaryWin(BigDecimal betSize, int betLevel) {
        return finish(requireGen().ordinaryWin(random), betSize, betLevel);
    }

    public RoundResult generateOrdinaryWin(BigDecimal betSize, int betLevel, RandomGenerator rng) {
        return finish(requireGen().ordinaryWin(rng), betSize, betLevel);
    }

    public RoundResult generateSpecial(BigDecimal betSize, int betLevel) {
        return finish(requireGen().special(random), betSize, betLevel);
    }

    public RoundResult generateSpecial(BigDecimal betSize, int betLevel, RandomGenerator rng) {
        return finish(requireGen().special(rng), betSize, betLevel);
    }

    public RoundResult generateLuckyRespin(BigDecimal betSize, int betLevel, RandomGenerator rng) {
        return finish(requireGen().luckyRespin(rng), betSize, betLevel);
    }

    public RoundResult generateMultiplierWheel(BigDecimal betSize, int betLevel, RandomGenerator rng) {
        return finish(requireGen().multiplierWheel(rng), betSize, betLevel);
    }

    public RoundResult restore(RoundFacts facts) {
        RoundResult restored = roundFactory.restore(facts);
        verifier.verify(restored);
        return restored;
    }

    public RoundResult restore(RoundCandidate candidate, BigDecimal betSize, int betLevel) {
        return finish(candidate, betSize, betLevel);
    }

    public int trainingKernelCount() {
        return requireGen().trainingKernelCount();
    }

    private RoundResult finish(RoundCandidate candidate, BigDecimal betSize, int betLevel) {
        if (!GameRules.legalBet(betSize, betLevel)) throw new IllegalArgumentException("非法下注档位");
        RoundResult result = roundFactory.create(candidate, betSize, betLevel);
        verifier.verify(result);
        return result;
    }

    private RandomCandidateGenerator requireGen() {
        if (candidates == null || random == null) throw new IllegalStateException("restore-only GameRuleCore 禁止生成");
        return candidates;
    }
}
