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
    private final RoundFactory roundFactory;
    private final RoundVerifier verifier;
    private final RandomGenerator random;
    private final IndependentLossGenerator losses;
    private final IndependentWin wins;
    private final IndependentSpecial specials;

    public GameRuleCore() {
        this(SymbolWeights.empiricalDefaults(), ReelPatterns.defaults(),
                new RoundFactory(), new RoundVerifier(), new SecureRandom());
    }

    public GameRuleCore(SymbolWeights weights) {
        this(weights, ReelPatterns.defaults(), new RoundFactory(), new RoundVerifier(), new SecureRandom());
    }

    public GameRuleCore(SymbolWeights weights, ReelPatterns patterns) {
        this(weights, patterns, new RoundFactory(), new RoundVerifier(), new SecureRandom());
    }

    private GameRuleCore(SymbolWeights weights, ReelPatterns patterns, RoundFactory roundFactory,
                         RoundVerifier verifier, RandomGenerator random) {
        this.roundFactory = roundFactory;
        this.verifier = verifier;
        this.random = random;
        if (random == null) {
            this.losses = null;
            this.wins = null;
            this.specials = null;
        } else {
            SymbolWeights resolved = weights == null ? SymbolWeights.empiricalDefaults() : weights;
            ReelPatterns shapes = patterns == null ? ReelPatterns.defaults() : patterns;
            this.losses = new IndependentLossGenerator(resolved, shapes);
            this.wins = new IndependentWin(resolved, shapes);
            this.specials = new IndependentSpecial(resolved, shapes);
        }
    }

    public static GameRuleCore forTesting(long seed) {
        return new GameRuleCore(SymbolWeights.empiricalDefaults(), ReelPatterns.defaults(),
                new RoundFactory(RoundFactory.deterministicIdentitySource(seed ^ 0x50C47L)),
                new RoundVerifier(), new SplittableRandom(seed));
    }

    public static GameRuleCore forRestoration() {
        return new GameRuleCore(null, null, new RoundFactory(), new RoundVerifier(), null);
    }

    public RoundResult generateIndependentLoss(BigDecimal betSize, int betLevel) {
        requireRandom();
        return finish(losses.generate(random), betSize, betLevel);
    }

    public RoundResult generateIndependentLoss(BigDecimal betSize, int betLevel, RandomGenerator rng) {
        requireRandom();
        return finish(losses.generate(rng), betSize, betLevel);
    }

    public RoundResult generateOrdinaryWin(BigDecimal betSize, int betLevel) {
        requireRandom();
        return finish(wins.generate(random), betSize, betLevel);
    }

    public RoundResult generateOrdinaryWin(BigDecimal betSize, int betLevel, RandomGenerator rng) {
        requireRandom();
        return finish(wins.generate(rng), betSize, betLevel);
    }

    public RoundResult generateSpecial(BigDecimal betSize, int betLevel) {
        requireRandom();
        return finish(specials.generate(random), betSize, betLevel);
    }

    public RoundResult generateSpecial(BigDecimal betSize, int betLevel, RandomGenerator rng) {
        requireRandom();
        return finish(specials.generate(rng), betSize, betLevel);
    }

    public RoundResult generateLuckyRespin(BigDecimal betSize, int betLevel, RandomGenerator rng) {
        requireRandom();
        return finish(specials.lucky(rng), betSize, betLevel);
    }

    public RoundResult generateMultiplierWheel(BigDecimal betSize, int betLevel, RandomGenerator rng) {
        requireRandom();
        return finish(specials.wheel(rng), betSize, betLevel);
    }

    public RoundResult restore(RoundFacts facts) {
        RoundResult restored = roundFactory.restore(facts);
        verifier.verify(restored);
        return restored;
    }

    public RoundResult restore(RoundCandidate candidate, BigDecimal betSize, int betLevel) {
        return finish(candidate, betSize, betLevel);
    }

    private RoundResult finish(RoundCandidate candidate, BigDecimal betSize, int betLevel) {
        RoundResult result = roundFactory.create(candidate, betSize, betLevel);
        verifier.verify(result);
        return result;
    }

    private void requireRandom() {
        if (random == null) throw new IllegalStateException("restore-only GameRuleCore 禁止生成");
    }
}
