package com.hd.cpgame.magicscroll2.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Creates a paid start and every continuation of one complete confirmed Round atomically. */
public final class RoundFactory {
    private final SecureRandom seedSource;
    private final GenerationPolicy policy;
    private final SymbolWeightPolicy symbolWeightPolicy;
    private final ResultUtil resultUtil;
    private final RoundVerifier verifier;

    RoundFactory(SecureRandom random, GenerationPolicy policy, ResultUtil resultUtil,
                 SymbolWeightPolicy symbolWeightPolicy) {
        this.seedSource = random;
        this.policy = policy;
        this.symbolWeightPolicy = symbolWeightPolicy;
        this.resultUtil = resultUtil;
        this.verifier = new RoundVerifier(resultUtil, policy);
    }

    public GeneratedRound create(BigDecimal paidBet, RoundMode requestedMode) {
        return createWithSeed(paidBet, requestedMode, seedSource.nextLong());
    }

    GeneratedRound createWithSeed(BigDecimal paidBet, RoundMode requestedMode, long deterministicSeed) {
        requirePaidBet(paidBet);
        if (requestedMode == null) throw new IllegalArgumentException("requestedMode is required");
        RandomCandidateGenerator candidates = new RandomCandidateGenerator(
                deterministicRandom(deterministicSeed), policy, symbolWeightPolicy);
        BigDecimal baseBet = baseBet(paidBet);
        List<RoundStep> steps = new ArrayList<RoundStep>();
        BigDecimal cumulative = money(BigDecimal.ZERO);

        switch (requestedMode) {
            case LOSS: {
                steps.add(step(candidates.terminalLoss(3, resultUtil, baseBet), 3, 1,
                        BigDecimal.ZERO, cumulative, true));
                break;
            }
            case BASE_WIN: {
                String winningBoard = candidates.ordinaryWin(3);
                cumulative = appendEvaluated(steps, winningBoard, 3, 1, baseBet, cumulative, false);
                steps.add(step(candidates.collapseToTerminal(winningBoard, 3, 4, resultUtil,
                                baseBet, 1), 4, 1,
                        BigDecimal.ZERO, cumulative, true));
                break;
            }
            case XSPLIT: {
                String activation = candidates.xSplitActivation(3);
                ResultUtil.Inspection activationResult = resultUtil.inspect(activation, 3, baseBet, 1);
                if (!activationResult.hasXSplit() || activationResult.getWin().signum() != 0) {
                    throw new IllegalStateException("xSplit activation candidate is invalid");
                }
                steps.add(step(activation, 3, 1, BigDecimal.ZERO, cumulative, false));
                String resolved = candidates.resolveXSplit(activation, 3);
                cumulative = appendEvaluated(steps, resolved, 3, 1,
                        baseBet, cumulative, false);
                steps.add(step(candidates.collapseToTerminal(resolved, 3, 4, resultUtil,
                                baseBet, 1), 4, 1,
                        BigDecimal.ZERO, cumulative, true));
                break;
            }
            case XBOMB_WILD: {
                String activation = candidates.xBombWin(3);
                ResultUtil.Inspection activationResult = resultUtil.inspect(activation, 3, baseBet, 1);
                if (!activationResult.hasWild() || activationResult.getWin().signum() <= 0) {
                    throw new IllegalStateException("xBomb Wild activation candidate is invalid");
                }
                cumulative = appendEvaluated(steps, activation, 3, 1, baseBet, cumulative, false);
                int nextMultiplier = 1 + activationResult.getWildCountValue();
                steps.add(step(candidates.collapseToTerminal(activation, 3, 4, resultUtil,
                                baseBet, nextMultiplier), 4, nextMultiplier,
                        BigDecimal.ZERO, cumulative, true));
                break;
            }
            default:
                throw new IllegalArgumentException("round mode is not confirmed for generation");
        }

        String roundKey = roundKeyFor(deterministicSeed, requestedMode, paidBet);
        GeneratedRound round = new GeneratedRound(GameConstants.ROUND_SCHEMA_VERSION,
                GameConstants.RULES_VERSION, GameConstants.RULES_HASH, deterministicSeed,
                roundKey, requestedMode, paidBet, cumulative, steps);
        verifier.verify(round);
        return round;
    }

    String createIndependentLossFormation(int activeRows, BigDecimal paidBet) {
        requirePaidBet(paidBet);
        RandomCandidateGenerator candidates = new RandomCandidateGenerator(
                deterministicRandom(seedSource.nextLong()), policy, symbolWeightPolicy);
        return candidates.terminalLoss(activeRows, resultUtil, baseBet(paidBet));
    }

    private static SecureRandom deterministicRandom(long seed) {
        try {
            SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
            random.setSeed(seed);
            return random;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("deterministic test-compatible PRNG is unavailable", e);
        }
    }

    static String roundKeyFor(long deterministicSeed, RoundMode mode, BigDecimal paidBet) {
        String keyMaterial = GameConstants.GAME_ID + "|" + GameConstants.RULES_VERSION + "|"
                + deterministicSeed + "|" + mode.name() + "|" + paidBet.toPlainString();
        return UUID.nameUUIDFromBytes(keyMaterial.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private BigDecimal appendEvaluated(List<RoundStep> steps, String formation, int activeRows,
                                       int globalMultiplier, BigDecimal baseBet,
                                       BigDecimal cumulative, boolean terminal) {
        BigDecimal win = resultUtil.inspect(formation, activeRows, baseBet, globalMultiplier).getWin();
        if (win.signum() <= 0) throw new IllegalStateException("winning candidate has no payout");
        BigDecimal next = money(cumulative.add(win));
        steps.add(step(formation, activeRows, globalMultiplier, win, next, terminal));
        return next;
    }

    private static RoundStep step(String formation, int activeRows, int globalMultiplier,
                                  BigDecimal win, BigDecimal cumulative, boolean terminal) {
        return new RoundStep(formation, activeRows, globalMultiplier, money(win), money(cumulative), terminal);
    }

    static BigDecimal baseBet(BigDecimal paidBet) {
        return paidBet.divide(GameConstants.BASE_BET_DIVISOR, 6, RoundingMode.HALF_UP);
    }

    static void requirePaidBet(BigDecimal paidBet) {
        if (paidBet == null || paidBet.compareTo(GameConstants.MINIMUM_TOTAL_BET) < 0) {
            throw new IllegalArgumentException("paid bet must be at least 0.40 BRL");
        }
    }

    static BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP); }
}
