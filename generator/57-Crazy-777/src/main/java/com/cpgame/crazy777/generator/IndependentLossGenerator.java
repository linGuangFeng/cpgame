package com.cpgame.crazy777.generator;

import com.cpgame.crazy777.generator.model.RoundCandidate;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/** Constructed 0-win board. Does not load crazy777-joint-kernels.txt. */
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
        String[][] colors = {{"H1", "H4"}, {"H2", "H5"}, {"H3", "H6"}};
        int c0 = random.nextInt(3), c1 = (c0 + 1) % 3;
        int[] color = {c0, c1, c0};
        List<String> board = new ArrayList<>(15);
        for (int reel = 0; reel < 3; reel++) {
            String[] pair = colors[color[reel]];
            board.add("BLANK");
            board.add(pair[random.nextInt(2)]);
            board.add("BLANK");
            board.add(pair[random.nextInt(2)]);
            board.add("BLANK");
        }
        return new RoundCandidate(List.of(board));
    }

    public static boolean isIndependentLoss(RoundCandidate c) {
        return c.boards().size() == 1 && !ResultUtil.isScatterTrigger(c.boards().get(0))
                && ResultUtil.evaluateRegularLines(c.boards().get(0)).isEmpty();
    }

    public double measureFirstAttemptLossSuccess(RandomGenerator random, int samples) {
        for (int i = 0; i < samples; i++) {
            List<String> board = candidate(random).boards().get(0);
            if (ResultUtil.isScatterTrigger(board) || !ResultUtil.evaluateRegularLines(board).isEmpty()) {
                return 0.0d;
            }
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
