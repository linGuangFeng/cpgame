package com.cpgame.crazypiggy.generator;

import com.cpgame.crazypiggy.generator.model.RoundCandidate;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/** Constructed 0-win board. Does not load crazy-piggy-joint-kernels.txt. */
public final class IndependentLossGenerator {
    private static final List<RoundCandidate> DEFAULTS = defaults();

    public RoundCandidate generate(RandomGenerator random) {
        for (int attempt = 0; attempt < 5; attempt++) {
            RoundCandidate candidate = candidate(random);
            if (isIndependentLoss(candidate)) return candidate;
        }
        return DEFAULTS.get(random.nextInt(10));
    }

    public RoundCandidate candidate(RandomGenerator random) {
        String[] pool = {"HOT", "SEV", "H2", "H3", "H4", "H5", "H6"};
        String[] cells = new String[9];
        for (int i = 0; i < 6; i++) cells[i] = pool[random.nextInt(pool.length)];
        cells[6] = cells[7] = cells[8] = "H7";
        return new RoundCandidate(List.of(cells), List.of(), List.of());
    }

    public static boolean isIndependentLoss(RoundCandidate c) {
        return c.wheelPositions().isEmpty() && c.wheelMultipliers().isEmpty()
                && ResultUtil.evaluateLines(c.symbols()).isEmpty();
    }

    public double measureFirstAttemptLossSuccess(RandomGenerator random, int samples) {
        for (int i = 0; i < samples; i++) {
            if (!ResultUtil.evaluateLines(candidate(random).symbols()).isEmpty()) return 0.0d;
        }
        return 1.0d;
    }

    private static List<RoundCandidate> defaults() {
        IndependentLossGenerator generator = new IndependentLossGenerator();
        java.security.SecureRandom random = new java.security.SecureRandom();
        List<RoundCandidate> defaults = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
            RoundCandidate c = generator.candidate(random);
            if (!isIndependentLoss(c)) throw new ExceptionInInitializerError("invalid constructed loss");
            defaults.add(c);
        }
        return List.copyOf(defaults);
    }
}
