package com.hd.cpgame.jungleparty;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Independently classifies a complete Round for the Redis multiplier contract. */
public final class ResultUtil {
    private ResultUtil() {}

    /**
     * Integer win multiplier for Redis buckets.
     * Unit is {@code betSize × betLevel} only — payline count does not enter the divisor.
     * Zero award is the loss bucket.
     */
    public static int multiplier(GameRuleCore.Round round) {
        IndependentVerifier.Verification verification = IndependentVerifier.verify(round);
        if (!verification.pass()) throw new IllegalArgumentException("invalid Round: " + verification.errors());
        if (round.totalAward().signum() == 0) return 0;
        BigDecimal unit = round.betSize().multiply(BigDecimal.valueOf(round.betLevel()));
        return round.totalAward().divide(unit, 0, RoundingMode.UNNECESSARY).intValueExact();
    }

    public static boolean special(GameRuleCore.Round round) {
        return round.scenario() == GameRuleCore.Scenario.SCATTER_FREE_ROUNDS;
    }
}
