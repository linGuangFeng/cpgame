package com.cpgame.batcha.g32;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;

public final class GeneratorSelfTest {
    public static void main(String[] args) {
        int failures = 0;
        failures += check("paid-bet", GameRuleCore.paidBet(new BigDecimal("0.02"), 10).compareTo(new BigDecimal("4")) == 0);
        List<String> sample = List.of(
            "1H4", "1A", "1H2", "1H2", "1H2",
            "1T", "4H6", "1H3",
            "1A", "1H3", "2T", "1T",
            "1H1", "1J", "1H4", "2Q",
            "2T", "1K", "3H4",
            "1Q", "1H3", "1J", "1H5", "1K", "1K", "1Scat");
        failures += check("legal-loss-board", GameRuleCore.legalSpecials(sample));
        GameRuleCore.BoardResult result = GameRuleCore.evaluateBoard(sample, new BigDecimal("0.02"), 10, 1);
        failures += check("sample-loss", result.winAmount().signum() == 0);
        CompleteRoundFactory factory = new CompleteRoundFactory(12, 10);
        IndependentVerifier verifier = new IndependentVerifier(new BigDecimal("20000"), 12, 10);
        MemberCodec codec = new MemberCodec();
        SecureRandom random = new SecureRandom();
        for (RoundMode mode : RoundMode.values()) {
            CompleteRound round = factory.generate(mode, random, new BigDecimal("0.02"), 10);
            verifier.verify(round);
            verifier.verifyCodecRoundTrip(round, codec);
            failures += check(mode.name(), round.mode() == mode);
        }
        if (failures != 0) {
            System.err.println("SELF_TEST_FAILURES=" + failures);
            System.exit(1);
        }
        System.out.println("SELF_TEST_OK rules=" + GameRuleCore.RULES_VERSION);
    }

    private static int check(String name, boolean ok) {
        if (!ok) System.err.println("FAIL " + name);
        return ok ? 0 : 1;
    }
}
