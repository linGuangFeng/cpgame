package com.cpgame.luckycatii;

import com.cpgame.luckycatii.model.RoundCandidate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Ordinary win on patterned reels. Locked payline cells stay; the rest of each reel
 * is completed as AAB/BAA/AAA/ABC. Wheel/Lucky still go to Mary.
 */
public final class IndependentWin {
    static final int MIN_UNITS = GameRules.PAYTABLE.get("S6");
    static final int MAX_UNITS = GameRules.PAYTABLE.get("WILD") * GameRules.PAYLINE_COUNT;
    private static final List<RoundCandidate> DEFAULTS = defaults();
    private final Map<String, Integer> weights;
    private final ReelPatterns patterns;

    public IndependentWin() {
        this(SymbolWeights.empiricalDefaults(), ReelPatterns.defaults());
    }

    public IndependentWin(SymbolWeights symbolWeights) {
        this(symbolWeights, ReelPatterns.defaults());
    }

    public IndependentWin(SymbolWeights symbolWeights, ReelPatterns patterns) {
        this.weights = symbolWeights.normal();
        this.patterns = patterns;
    }

    public RoundCandidate generate(RandomGenerator random) {
        for (int attempt = 0; attempt < 5; attempt++) {
            RoundCandidate candidate = candidate(random);
            if (valid(candidate)) return candidate;
        }
        return DEFAULTS.get(random.nextInt(10));
    }

    public RoundCandidate candidate(RandomGenerator random) {
        int ticket = random.nextInt(10);
        if (ticket < 3) return overlay(random, 1, payAt(random, 0));
        if (ticket < 6) return overlay(random, 2 + random.nextInt(2), payAt(random, 1));
        if (ticket < 8) return wildHeavy(random);
        return overlay(random, 3 + random.nextInt(2), payAt(random, 2));
    }

    private RoundCandidate overlay(RandomGenerator random, int lineCount, String symbol) {
        boolean[] used = pickLines(lineCount, random);
        String[] cells = new String[9];
        boolean sprinkleWild = random.nextInt(4) == 0;
        int wildLine = -1, wildReel = -1;
        if (sprinkleWild) {
            for (int line = 0; line < used.length; line++) {
                if (!used[line]) continue;
                wildLine = line;
                wildReel = random.nextInt(3);
                break;
            }
        }
        for (int reel = 0; reel < 3; reel++) {
            boolean[] locked = new boolean[3];
            String[] lockedSymbol = new String[3];
            for (int line = 0; line < used.length; line++) {
                if (!used[line]) continue;
                int row = GameRules.PAYLINE_ROWS[line][reel];
                locked[row] = true;
                lockedSymbol[row] = (line == wildLine && reel == wildReel) ? "WILD" : symbol;
            }
            Dealing.completeReel(cells, reel, locked, lockedSymbol, patterns.pick(random), weights, random);
        }
        if (ResultUtil.isWheelBoard(Dealing.freeze(cells))) {
            Dealing.fillReel(cells, 2, ReelPatterns.Shape.ABC, weights, random);
        }
        return frozen(cells);
    }

    /** Two different pays among WILDs: legal ordinary, not Wheel. */
    private RoundCandidate wildHeavy(RandomGenerator random) {
        String[] cells = new String[9];
        for (int i = 0; i < 9; i++) cells[i] = "WILD";
        int first = random.nextInt(9);
        int second = (first + 1 + random.nextInt(8)) % 9;
        String a = payAt(random, 2);
        cells[first] = a;
        cells[second] = Dealing.otherPay(a, weights, random);
        return frozen(cells);
    }

    static boolean valid(RoundCandidate candidate) {
        if (candidate.luckyRespin() || candidate.rpx() != 1 || !candidate.paidBoard().equals(candidate.finalBoard()))
            return false;
        if (!Dealing.legal(candidate.finalBoard())) return false;
        if (ResultUtil.isWheelBoard(candidate.finalBoard())) return false;
        if (ResultUtil.findLuckyTrigger(candidate.paidBoard()) != null) return false;
        Map<Integer, String> wins = ResultUtil.evaluatePaylines(candidate.finalBoard());
        if (wins.isEmpty()) return false;
        int units = Dealing.lineUnits(candidate.finalBoard());
        return units >= MIN_UNITS && units <= MAX_UNITS;
    }

    private static String payAt(RandomGenerator random, int band) {
        if (band <= 0) {
            int ticket = random.nextInt(10);
            if (ticket < 4) return "S6";
            if (ticket < 7) return "S5";
            return "S4";
        }
        if (band == 1) {
            int ticket = random.nextInt(10);
            if (ticket < 3) return "S4";
            if (ticket < 6) return "S3";
            if (ticket < 8) return "S2";
            return "S1";
        }
        return random.nextBoolean() ? "S1" : "S2";
    }

    static boolean[] pickLines(int lineCount, RandomGenerator random) {
        int want = Math.max(1, Math.min(4, lineCount));
        boolean[] used = new boolean[GameRules.PAYLINE_ROWS.length];
        int got = 0;
        while (got < want) {
            int line = random.nextInt(GameRules.PAYLINE_ROWS.length);
            if (used[line]) continue;
            used[line] = true;
            if (used[0] && used[1] && used[2]) {
                used[line] = false;
                continue;
            }
            got++;
        }
        return used;
    }

    private static RoundCandidate frozen(String[] cells) {
        List<String> board = Dealing.freeze(cells);
        return new RoundCandidate(board, board, 1, false);
    }

    private static List<RoundCandidate> defaults() {
        IndependentWin generator = new IndependentWin();
        java.security.SecureRandom random = new java.security.SecureRandom();
        List<RoundCandidate> defaults = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
            RoundCandidate chosen = null;
            for (int n = 0; n < 40; n++) {
                RoundCandidate candidate = generator.candidate(random);
                if (valid(candidate)) {
                    chosen = candidate;
                    break;
                }
            }
            if (chosen == null) throw new ExceptionInInitializerError("invalid constructed ordinary win");
            defaults.add(chosen);
        }
        return List.copyOf(defaults);
    }
}
