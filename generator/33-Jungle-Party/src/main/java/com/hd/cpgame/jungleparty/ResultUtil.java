package com.hd.cpgame.jungleparty;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Independently classifies a complete Round for the Redis multiplier contract. */
public final class ResultUtil {
    private ResultUtil() {}
    /** Integer hundredths of paid-bet return; zero is the loss bucket. */
    public static int multiplier(GameRuleCore.Round round) {
        IndependentVerifier.Verification verification = IndependentVerifier.verify(round);
        if (!verification.pass()) throw new IllegalArgumentException("invalid Round: " + verification.errors());
        if (round.totalAward().signum() == 0) return 0;
        return round.totalAward().multiply(BigDecimal.valueOf(100))
            .divide(round.paidBet(), 0, RoundingMode.HALF_UP).intValueExact();
    }
    public static boolean special(GameRuleCore.Round round) { return round.scenario() == GameRuleCore.Scenario.SCATTER_FREE_ROUNDS; }
}
