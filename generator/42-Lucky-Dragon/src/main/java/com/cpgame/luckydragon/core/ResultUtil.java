package com.cpgame.luckydragon.core;

import java.math.BigDecimal;

/** 独立从最小事实反推实际 payout multiplier 和 Outcome。 */
public final class ResultUtil {
    private ResultUtil() { }

    public static SpinResult analyze(RoundFacts facts) {
        return analyze(new GameRuleCore(), facts);
    }

    public static SpinResult analyze(GameRuleCore core, RoundFacts facts) {
        return core.evaluate(new RoundRequest(facts.betSize(), facts.betLevel()),
            facts.symbols(), facts.reelMultiplier());
    }

    public static int positiveMultiplier(RoundFacts facts) {
        SpinResult result = analyze(facts);
        return positiveMultiplier(facts, result);
    }

    public static int positiveMultiplier(GameRuleCore core, RoundFacts facts) {
        return positiveMultiplier(facts, analyze(core, facts));
    }

    private static int positiveMultiplier(RoundFacts facts, SpinResult result) {
        if (result.payout().signum() == 0) return 0;
        BigDecimal paidBet = facts.betSize().multiply(BigDecimal.valueOf(facts.betLevel()));
        return result.payout().divide(paidBet).intValueExact();
    }
}
