package com.cpgame.luckydragon.core;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;

/** One-pass catalog: missing odds floor, then a single combo lookup. */
public final class LuckyDragonMultiplierCatalogTestMain {
    public static void main(String[] args) {
        List<Integer> odds = LuckyDragonMultiplierCatalog.oddsList();
        if (!odds.get(0).equals(0)) throw new AssertionError("odds list must start at 0: " + odds);
        int max = odds.get(odds.size() - 1);
        if (max != 999) throw new AssertionError("expected max odd 999, got " + odds);
        if (LuckyDragonMultiplierCatalog.floorOdd(1000) != 999) {
            throw new AssertionError("1000 should floor to 999, got " + LuckyDragonMultiplierCatalog.floorOdd(1000));
        }
        if (LuckyDragonMultiplierCatalog.floorOdd(999) != 999) throw new AssertionError("999 should stay 999");
        if (LuckyDragonMultiplierCatalog.floorOdd(4) != 0) throw new AssertionError("4 should floor to 0");
        if (LuckyDragonMultiplierCatalog.floorOdd(-10) != 0) throw new AssertionError("negative should floor to 0");
        if (LuckyDragonMultiplierCatalog.floorOdd(20) != 15) throw new AssertionError("20 should floor to 15");

        GameRuleCore rules = new GameRuleCore();
        RandomRoundGenerator generator = new RandomRoundGenerator(rules, new SecureRandom());
        IndependentRoundVerifier verifier = new IndependentRoundVerifier(rules);
        RoundRequest request = new RoundRequest(new BigDecimal("0.5"), 1);

        SpinResult over = generator.generate(request, 1000);
        verifier.verify(request, over);
        if (ResultUtil.positiveMultiplier(request, over) != 999) {
            throw new AssertionError("generate(1000) must emit 999, got " + over);
        }
        RoundRequest customStake = new RoundRequest(new BigDecimal("7.7"), 12);
        SpinResult custom = generator.generate(customStake, 1000);
        verifier.verify(customStake, custom);
        if (ResultUtil.positiveMultiplier(customStake, custom) != 999) {
            throw new AssertionError("non-list stake must still generate: " + custom);
        }

        SpinResult under = generator.generate(request, 4);
        verifier.verify(request, under);
        if (ResultUtil.positiveMultiplier(request, under) != 0 || under.outcome() != OutcomeType.LOSS) {
            throw new AssertionError("generate(4) must emit 0, got " + under);
        }

        for (int odd : odds) {
            SpinResult result = generator.generate(request, odd);
            verifier.verify(request, result);
            if (ResultUtil.positiveMultiplier(request, result) != odd) {
                throw new AssertionError("generate(" + odd + ") mismatch: " + result);
            }
        }

        for (int i = 0; i < 200; i++) {
            SpinResult sampled = generator.next(request);
            verifier.verify(request, sampled);
            int got = ResultUtil.positiveMultiplier(request, sampled);
            if (!odds.contains(got)) throw new AssertionError("sampled odd not on list: " + got);
        }

        System.out.println("LuckyDragonMultiplierCatalogTestMain PASS odds=" + odds
                + " floor1000=" + LuckyDragonMultiplierCatalog.floorOdd(1000));
    }
}
