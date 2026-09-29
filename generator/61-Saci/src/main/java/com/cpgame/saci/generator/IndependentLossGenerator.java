package com.cpgame.saci.generator;

import com.cpgame.saci.generator.model.RoundCandidate;
import com.cpgame.saci.generator.model.RoundMode;
import com.cpgame.saci.generator.model.StepFact;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/** Constructed 0-win Ways board. Does not load saci-joint-kernels.txt. */
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
        List<String> ordinary = new ArrayList<>(List.of("1", "2", "3", "4", "5", "6", "7", "8"));
        List<String> first = ordinary.subList(0, 4);
        List<String> second = ordinary.subList(4, ordinary.size());
        List<String> rskl = new ArrayList<>(15);
        for (int row = 0; row < 3; row++) rskl.add("1" + first.get(random.nextInt(first.size())) + "1");
        for (int row = 0; row < 3; row++) rskl.add("1" + second.get(random.nextInt(second.size())) + "1");
        for (int reel = 2; reel < 5; reel++)
            for (int row = 0; row < 3; row++) rskl.add("1" + ordinary.get(random.nextInt(ordinary.size())) + "1");
        StepFact step = new StepFact(rskl, List.of(), List.of(), List.of(), 0, 1, 0, 0, 0, 0, 1, 0, 0);
        return new RoundCandidate(RoundMode.ORDINARY_LOSS, List.of(step));
    }

    public static boolean isIndependentLoss(RoundCandidate c) {
        return c.mode() == RoundMode.ORDINARY_LOSS && c.steps().size() == 1
                && ResultUtil.scatterCount(c.steps().get(0).rskl()) < 3
                && ResultUtil.expectedWa(c.steps().get(0).rskl(), 1, new java.math.BigDecimal("0.02")).signum() == 0;
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
