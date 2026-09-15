package com.cpgame.luckydragon.core;

import java.math.BigDecimal;

/** JDK17 clean-build gate: at least 100000 natural rounds independently re-evaluated. */
public final class GameRuleCore100kTestMain {
    public static void main(String[] args) {
        GameRuleCore core = new GameRuleCore();
        RandomRoundGenerator generator = new RandomRoundGenerator(core);
        IndependentRoundVerifier verifier = new IndependentRoundVerifier(core);
        RoundRequest request = new RoundRequest(new BigDecimal("0.5"), 1);
        int losses = 0;
        for (int index = 0; index < 100_000; index++) {
            SpinResult result = generator.next(request);
            verifier.verify(request, result);
            if (result.payout().signum() == 0) losses++;
        }
        if (losses == 0) throw new AssertionError("natural generator did not produce a loss");
        System.out.println("GameRuleCore100kTestMain PASS rounds=100000 losses=" + losses);
    }
}
