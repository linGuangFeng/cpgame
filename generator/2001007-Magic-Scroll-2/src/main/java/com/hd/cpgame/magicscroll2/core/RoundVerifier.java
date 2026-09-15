package com.hd.cpgame.magicscroll2.core;

import java.math.BigDecimal;
import java.util.List;

/** Independently reverses emitted formations and rejects inconsistent or incomplete Rounds. */
public final class RoundVerifier {
    private final ResultUtil resultUtil;
    private final GenerationPolicy policy;
    private final AdjacentStepVerifier adjacentStepVerifier;

    public RoundVerifier() { this(new ResultUtil(), GenerationPolicy.defaults()); }

    RoundVerifier(ResultUtil resultUtil, GenerationPolicy policy) {
        this.resultUtil = resultUtil;
        this.policy = policy;
        this.adjacentStepVerifier = new AdjacentStepVerifier();
    }

    public Verification verify(GeneratedRound round) {
        if (round == null) throw new IllegalArgumentException("round is required");
        if (!GameConstants.ROUND_SCHEMA_VERSION.equals(round.getSchemaVersion())) {
            throw new IllegalStateException("Round schemaVersion does not match the current core");
        }
        if (!GameConstants.RULES_VERSION.equals(round.getRulesVersion())
                || !GameConstants.RULES_HASH.equals(round.getRulesHash())) {
            throw new IllegalStateException("Round rulesVersion/rulesHash does not match the current core");
        }
        if (round.getRoundKey() == null || round.getRoundKey().trim().isEmpty()) {
            throw new IllegalStateException("Round key is required");
        }
        String expectedRoundKey = RoundFactory.roundKeyFor(round.getDeterministicSeed(),
                round.getMode(), round.getPaidBet());
        if (!expectedRoundKey.equals(round.getRoundKey())) {
            throw new IllegalStateException("Round key does not match deterministic seed facts");
        }
        RoundFactory.requirePaidBet(round.getPaidBet());
        List<RoundStep> steps = round.getSteps();
        if (steps.isEmpty() || steps.size() > policy.getMaxRoundSteps()) {
            throw new IllegalStateException("Round step count violates configured limits");
        }
        BigDecimal baseBet = RoundFactory.baseBet(round.getPaidBet());
        BigDecimal cumulative = RoundFactory.money(BigDecimal.ZERO);
        int expectedMultiplier = 1;
        int consecutiveWins = 0;
        ResultUtil.Inspection first = null;
        int adjacentTransitions = 0;

        for (int index = 0; index < steps.size(); index++) {
            RoundStep step = steps.get(index);
            if (index > 0) {
                adjacentStepVerifier.verify(steps.get(index - 1), step, baseBet);
                adjacentTransitions++;
            }
            if (step.getGlobalMultiplier() != expectedMultiplier) {
                throw new IllegalStateException("global multiplier cannot be reversed at Delivery " + index);
            }
            ResultUtil.Inspection inspection = resultUtil.inspect(step.getFormation(), step.getActiveRows(),
                    baseBet, expectedMultiplier);
            if (first == null) first = inspection;
            if (inspection.getWin().compareTo(step.getStepWin()) != 0) {
                throw new IllegalStateException("step win mismatch at Delivery " + index);
            }
            cumulative = RoundFactory.money(cumulative.add(inspection.getWin()));
            if (cumulative.compareTo(step.getCumulativeWin()) != 0) {
                throw new IllegalStateException("cumulative win mismatch at Delivery " + index);
            }
            boolean derivedTerminal = resultUtil.isTerminalBaseStep(inspection);
            if (derivedTerminal != step.isTerminal()) {
                throw new IllegalStateException("terminal state mismatch at Delivery " + index);
            }
            if (step.isTerminal() != (index == steps.size() - 1)) {
                throw new IllegalStateException("Round terminates before or after its final Delivery");
            }
            if (inspection.getWin().signum() > 0) {
                consecutiveWins++;
                if (consecutiveWins > policy.getMaxConsecutiveWins()) {
                    throw new IllegalStateException("consecutive win limit exceeded");
                }
            } else {
                consecutiveWins = 0;
            }
            expectedMultiplier += inspection.getWildCountValue();
        }

        if (cumulative.compareTo(round.getPayout()) != 0) throw new IllegalStateException("Round payout mismatch");
        BigDecimal payoutMultiplier = cumulative.divide(round.getPaidBet(), 8, java.math.RoundingMode.HALF_UP);
        if (payoutMultiplier.compareTo(BigDecimal.valueOf(policy.getMaxTotalWinMultiplier())) > 0) {
            throw new IllegalStateException("Round total win multiplier limit exceeded");
        }
        RoundMode inferred = inferMode(first, steps);
        if (inferred != round.getMode()) throw new IllegalStateException("declared mode differs from reversed mode");
        return new Verification(inferred, cumulative, steps.size(), adjacentTransitions);
    }

    RoundMode inferMode(ResultUtil.Inspection first, List<RoundStep> steps) {
        if (first.hasXSplit()) return RoundMode.XSPLIT;
        if (first.hasWild()) return RoundMode.XBOMB_WILD;
        if (steps.size() == 1 && first.getWin().signum() == 0) return RoundMode.LOSS;
        if (first.getWin().signum() > 0) return RoundMode.BASE_WIN;
        throw new IllegalStateException("Round mode is not one of the confirmed generation modes");
    }

    public static final class Verification {
        private final RoundMode inferredMode;
        private final BigDecimal payout;
        private final int deliveryCount;
        private final int adjacentTransitionCount;

        Verification(RoundMode inferredMode, BigDecimal payout, int deliveryCount,
                     int adjacentTransitionCount) {
            this.inferredMode = inferredMode;
            this.payout = payout;
            this.deliveryCount = deliveryCount;
            this.adjacentTransitionCount = adjacentTransitionCount;
        }

        public RoundMode getInferredMode() { return inferredMode; }
        public BigDecimal getPayout() { return payout; }
        public int getDeliveryCount() { return deliveryCount; }
        public int getAdjacentTransitionCount() { return adjacentTransitionCount; }
    }
}
