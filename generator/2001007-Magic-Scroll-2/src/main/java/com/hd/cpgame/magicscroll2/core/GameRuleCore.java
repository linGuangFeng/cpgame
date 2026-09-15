package com.hd.cpgame.magicscroll2.core;

import java.math.BigDecimal;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/** Single formal generation chain used by Loader and Demo API. */
public final class GameRuleCore {
    private final SecureRandom random;
    private final TrialProbabilityPolicy trialProbabilityPolicy;
    private final RoundFactory roundFactory;
    private final ResultUtil resultUtil;
    private final RoundVerifier verifier;
    private final MinimalFactCodec minimalFactCodec;
    private final DeliveryRuntimeUtil deliveryRuntimeUtil;

    public GameRuleCore(GenerationPolicy policy, TrialProbabilityPolicy trialProbabilityPolicy) {
        this(newStrongRandom(), policy, trialProbabilityPolicy, SymbolWeightPolicy.localReplicaDefaults());
    }

    public GameRuleCore(GenerationPolicy policy, TrialProbabilityPolicy trialProbabilityPolicy,
                        SymbolWeightPolicy symbolWeightPolicy) {
        this(newStrongRandom(), policy, trialProbabilityPolicy, symbolWeightPolicy);
    }

    GameRuleCore(SecureRandom random, GenerationPolicy policy,
                 TrialProbabilityPolicy trialProbabilityPolicy) {
        this(random, policy, trialProbabilityPolicy, SymbolWeightPolicy.localReplicaDefaults());
    }

    GameRuleCore(SecureRandom random, GenerationPolicy policy,
                 TrialProbabilityPolicy trialProbabilityPolicy, SymbolWeightPolicy symbolWeightPolicy) {
        if (random == null || policy == null || trialProbabilityPolicy == null || symbolWeightPolicy == null) {
            throw new IllegalArgumentException("random, generation policy and current-game weights are required");
        }
        this.random = random;
        this.trialProbabilityPolicy = trialProbabilityPolicy;
        this.resultUtil = new ResultUtil();
        this.roundFactory = new RoundFactory(random, policy, resultUtil, symbolWeightPolicy);
        this.verifier = new RoundVerifier(resultUtil, policy);
        this.minimalFactCodec = new MinimalFactCodec(resultUtil, policy);
        this.deliveryRuntimeUtil = new DeliveryRuntimeUtil(resultUtil, policy);
    }

    /** Selects a configured confirmed mode, then atomically generates and verifies its complete Round. */
    public GeneratedRound generateLiveRound(BigDecimal paidBet) {
        return roundFactory.create(paidBet, trialProbabilityPolicy.select(random));
    }

    public GeneratedRound generateCompleteRound(BigDecimal paidBet, RoundMode confirmedMode) {
        return roundFactory.create(paidBet, confirmedMode);
    }

    /** Explicit deterministic entry point for tests and fault reproduction only. */
    public GeneratedRound generateCompleteRound(BigDecimal paidBet, RoundMode confirmedMode,
                                                long deterministicSeed) {
        return roundFactory.createWithSeed(paidBet, confirmedMode, deterministicSeed);
    }

    public String generateIndependentLossFormation(int activeRows) {
        return roundFactory.createIndependentLossFormation(activeRows, GameConstants.MINIMUM_TOTAL_BET);
    }

    public ResultUtil resultUtil() { return resultUtil; }
    public RoundVerifier verifier() { return verifier; }
    public MinimalFactCodec minimalFactCodec() { return minimalFactCodec; }
    public DeliveryRuntimeUtil deliveryRuntimeUtil() { return deliveryRuntimeUtil; }

    private static SecureRandom newStrongRandom() {
        try {
            return SecureRandom.getInstanceStrong();
        } catch (NoSuchAlgorithmException e) {
            return new SecureRandom();
        }
    }
}
