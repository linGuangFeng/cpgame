package com.cpgame.luckynightmarket;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Independent 0-unit step by payline geometry. Column 0 and column 1 use disjoint
 * paying symbols and no wilds, so every left-to-right line breaks. No dealing-model.json.
 */
final class ConstructiveLossGenerator {
    private static final int[] PAYING = {1, 2, 3, 4, 5, 6};
    private final SecureRandom random = new SecureRandom();
    private final SymbolWeightSchedule symbolWeights;
    private final ZeroLossSupport<RoundFact.Step> ordinary;
    private final ZeroLossSupport<RoundFact.Step> feature;

    ConstructiveLossGenerator() {
        this(new SymbolWeightSchedule(new Properties()));
    }

    ConstructiveLossGenerator(SymbolWeightSchedule symbolWeights) {
        this.symbolWeights = symbolWeights;
        ordinary = new ZeroLossSupport<>(() -> candidate(), s -> valid(s, false), s -> s);
        feature = new ZeroLossSupport<>(() -> candidate(), s -> valid(s, true), s -> s);
    }

    RoundFact.Step next(boolean featureMode) {
        ZeroLossSupport<RoundFact.Step> pool = featureMode ? feature : ordinary;
        return pool.generate(this::candidate, random::nextInt);
    }

    RoundFact.Step candidate() {
        int[] partition = shuffle(PAYING);
        int[] left = Arrays.copyOfRange(partition, 0, 3);
        int[] right = Arrays.copyOfRange(partition, 3, 6);
        List<Integer> ps = new ArrayList<>(9);
        for (int row = 0; row < 3; row++) ps.add(symbolWeights.pick(random, left));
        for (int row = 0; row < 3; row++) ps.add(symbolWeights.pick(random, right));
        for (int row = 0; row < 3; row++) ps.add(symbolWeights.pick(random, PAYING));
        return new RoundFact.Step(ps, List.of(1, 1, 1), 0);
    }

    private int[] shuffle(int[] source) {
        int[] values = source.clone();
        for (int i = values.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = values[i];
            values[i] = values[j];
            values[j] = swap;
        }
        return values;
    }

    private static boolean valid(RoundFact.Step step, boolean featureMode) {
        return step != null && !step.wheel()
                && GameRuleCore.evaluate(step, featureMode).units() == 0
                && ResultUtil.evaluate(step, featureMode).units() == 0;
    }
}
