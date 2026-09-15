package com.cpgame.luckydragon.core;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.random.RandomGeneratorFactory;

public final class GameRuleCoreTestMain {
    public static void main(String[] args) {
        GameRuleCore rules = new GameRuleCore();
        RoundRequest request = new RoundRequest(new BigDecimal("0.5"), 1);
        assertPayout(rules.evaluate(request, List.of("H1","H1","H1"), 0), "55.50", OutcomeType.WIN);
        assertPayout(rules.evaluate(request, List.of("H1","WILD","H1"), 3), "166.50", OutcomeType.WILD_MULTIPLIER_X3);
        assertPayout(rules.evaluate(request, List.of("H2","WILD","H2"), 5), "52.50", OutcomeType.WILD_MULTIPLIER_X5);
        assertPayout(rules.evaluate(request, List.of("H2","WILD","WILD"), 9), "94.50", OutcomeType.WILD_MULTIPLIER_X9);
        assertPayout(rules.evaluate(request, List.of("H0","H0","H0"), 0), "0", OutcomeType.LOSS);

        RandomRoundGenerator generator = new RandomRoundGenerator(rules,
            RandomGeneratorFactory.<java.util.random.RandomGenerator>of("L64X128MixRandom").create(42));
        IndependentRoundVerifier verifier = new IndependentRoundVerifier(rules);
        for (int index = 0; index < 20_000; index++) verifier.verify(request, generator.next(request));
        assertRejectedModel(rules, "H2,H2,H2,0:100");
        assertRejectedModel(rules, "H4,H0,H0,0:100");
        SpinResult loss = generator.independentLoss(request);
        if (loss.payout().signum() != 0) throw new AssertionError("loss generator emitted payout");
        GameRound round = new GameRound("round:test", "42", request, loss,
            new BigDecimal("999.50"), Instant.EPOCH, 0, true);
        MinimalFactCodec codec = new MinimalFactCodec();
        if (!RoundFacts.from(round).equals(codec.decode(codec.encode(round)))) {
            throw new AssertionError("Redis complete fact codec mismatch");
        }
        System.out.println("GameRuleCoreTestMain PASS rulesHash=" + GameRuleCore.RULES_HASH);
    }

    private static void assertRejectedModel(GameRuleCore rules, String model) {
        try {
            new RandomRoundGenerator(rules,
                RandomGeneratorFactory.<java.util.random.RandomGenerator>of("L64X128MixRandom").create(7), model);
            throw new AssertionError("v38 observed symbol limit was not enforced: " + model);
        } catch (IllegalArgumentException expected) {
            // Expected: configured complete states may not exceed the frozen provider maxima.
        }
    }

    private static void assertPayout(SpinResult result, String payout, OutcomeType outcome) {
        if (result.payout().compareTo(new BigDecimal(payout)) != 0 || result.outcome() != outcome) {
            throw new AssertionError("unexpected result: " + result);
        }
    }
}
