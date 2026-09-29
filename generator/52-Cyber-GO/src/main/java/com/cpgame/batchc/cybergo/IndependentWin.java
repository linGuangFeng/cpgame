package com.cpgame.batchc.cybergo;

import static com.cpgame.batchc.cybergo.CyberGoRules.MINIMUM_BET_LEVEL;
import static com.cpgame.batchc.cybergo.CyberGoRules.MINIMUM_BET_SIZE;
import static com.cpgame.batchc.cybergo.CyberGoRules.PAYTABLE;
import static com.cpgame.batchc.cybergo.CyberGoRules.PAYING_SYMBOLS;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Constructed ordinary 243-ways win. One paying symbol is painted left-to-right on 3/4/5 reels
 * over a disjoint-reel loss base, so the hit is legal by the paytable rather than a capture kernel.
 */
final class IndependentWin {
    private static final int MAX_UNITS = 9000;
    private static final List<List<String>> DEFAULTS = defaults();

    private IndependentWin() { }

    static List<String> generate(RandomGenerator random) {
        for (int attempt = 0; attempt < 5; attempt++) {
            List<String> board = candidate(random);
            if (valid(board)) return board;
        }
        return DEFAULTS.get(random.nextInt(10));
    }

    static List<String> candidate(RandomGenerator random) {
        List<String> cells = new ArrayList<>(IndependentLoss.payingBoard(random));
        String hit = PAYING_SYMBOLS.get(weightedPay(random));
        int length = length(random);
        int perReel = perReel(random);
        for (int reel = 0; reel < length; reel++) {
            boolean[] used = new boolean[3];
            int placed = 0;
            int guard = 0;
            while (placed < perReel && guard++ < 12) {
                int row = random.nextInt(3);
                if (used[row]) continue;
                used[row] = true;
                cells.set(reel * 3 + row, hit);
                placed++;
            }
        }
        return List.copyOf(cells);
    }

    static List<String> threeKind(RandomGenerator random, String symbol) {
        List<String> cells = new ArrayList<>(IndependentLoss.payingBoard(random));
        int row = random.nextInt(3);
        for (int reel = 0; reel < 3; reel++) cells.set(reel * 3 + row, symbol);
        return List.copyOf(cells);
    }

    static boolean valid(List<String> board) {
        ResultUtil.Evaluation evaluation = ResultUtil.evaluate(board, MINIMUM_BET_LEVEL, MINIMUM_BET_SIZE);
        if (evaluation.scatterCount() >= 3 || evaluation.baseWin().signum() <= 0) return false;
        int units = evaluation.baseWin().divide(MINIMUM_BET_SIZE, 0, RoundingMode.UNNECESSARY).intValueExact();
        return units >= PAYTABLE.get("J").get(3) && units <= MAX_UNITS;
    }

    private static int weightedPay(RandomGenerator random) {
        int ticket = random.nextInt(100);
        if (ticket < 30) return 7;
        if (ticket < 55) return 6;
        if (ticket < 70) return 4;
        if (ticket < 82) return 5;
        if (ticket < 90) return 3;
        if (ticket < 95) return 2;
        if (ticket < 98) return 1;
        return 0;
    }

    private static int length(RandomGenerator random) {
        int ticket = random.nextInt(10);
        if (ticket < 7) return 3;
        if (ticket < 9) return 4;
        return 5;
    }

    private static int perReel(RandomGenerator random) {
        int ticket = random.nextInt(10);
        if (ticket < 7) return 1;
        if (ticket < 9) return 2;
        return 3;
    }

    private static List<List<String>> defaults() {
        java.security.SecureRandom random = new java.security.SecureRandom();
        List<List<String>> defaults = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
            List<String> chosen = threeKind(random, i % 2 == 0 ? "J" : "Q");
            if (!valid(chosen)) {
                chosen = threeKind(random, "J");
            }
            if (!valid(chosen)) throw new ExceptionInInitializerError("invalid constructed ordinary win");
            defaults.add(chosen);
        }
        return List.copyOf(defaults);
    }
}
