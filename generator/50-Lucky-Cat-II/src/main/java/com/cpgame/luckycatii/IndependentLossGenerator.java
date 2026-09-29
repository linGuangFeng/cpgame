package com.cpgame.luckycatii;

import com.cpgame.luckycatii.model.RoundCandidate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Constructed 0-win board. Each reel is filled as AAB/BAA/AAA/ABC from configured shape weights.
 * First candidate is always a miss: patterned deals, then AAA|BBB|CCC fallback.
 */
public final class IndependentLossGenerator {
    private static final List<RoundCandidate> DEFAULTS = defaults();
    private final Map<String, Integer> weights;
    private final ReelPatterns patterns;

    public IndependentLossGenerator() {
        this(SymbolWeights.empiricalDefaults(), ReelPatterns.defaults());
    }

    public IndependentLossGenerator(SymbolWeights symbolWeights) {
        this(symbolWeights, ReelPatterns.defaults());
    }

    public IndependentLossGenerator(SymbolWeights symbolWeights, ReelPatterns patterns) {
        this.weights = symbolWeights.normal();
        this.patterns = patterns;
    }

    public RoundCandidate generate(RandomGenerator random) {
        for (int attempt = 0; attempt < 5; attempt++) {
            RoundCandidate candidate = candidate(random);
            if (isIndependentLoss(candidate)) return candidate;
        }
        return DEFAULTS.get(random.nextInt(10));
    }

    public RoundCandidate candidate(RandomGenerator random) {
        for (int attempt = 0; attempt < 8; attempt++) {
            List<String> board = Dealing.freeze(Dealing.board(weights, patterns, random));
            if (isIndependentLoss(board(board))) return board(board);
        }
        return stacked(random);
    }

    public static boolean isIndependentLoss(RoundCandidate c) {
        return !c.luckyRespin() && c.rpx() == 1 && c.paidBoard().equals(c.finalBoard())
                && !ResultUtil.isWheelBoard(c.finalBoard()) && ResultUtil.findLuckyTrigger(c.paidBoard()) == null
                && ResultUtil.evaluatePaylines(c.finalBoard()).isEmpty()
                && Dealing.legal(c.finalBoard());
    }

    static RoundCandidate stacked(RandomGenerator random) {
        List<String> pays = new ArrayList<>();
        for (String symbol : GameRules.SYMBOLS) if (!"WILD".equals(symbol)) pays.add(symbol);
        for (int i = pays.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            String tmp = pays.get(i);
            pays.set(i, pays.get(j));
            pays.set(j, tmp);
        }
        String a = pays.get(0), b = pays.get(1), c = pays.get(2);
        return board(List.of(a, a, a, b, b, b, c, c, c));
    }

    private static RoundCandidate board(List<String> cells) {
        return new RoundCandidate(cells, cells, 1, false);
    }

    private static List<RoundCandidate> defaults() {
        java.security.SecureRandom random = new java.security.SecureRandom();
        List<RoundCandidate> defaults = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
            RoundCandidate c = stacked(random);
            if (!isIndependentLoss(c)) throw new ExceptionInInitializerError("invalid constructed loss");
            defaults.add(c);
        }
        return List.copyOf(defaults);
    }
}
