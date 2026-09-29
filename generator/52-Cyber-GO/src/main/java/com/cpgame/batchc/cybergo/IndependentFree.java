package com.cpgame.batchc.cybergo;

import static com.cpgame.batchc.cybergo.CyberGoRules.FREE_INITIAL_MULTIPLIER;
import static com.cpgame.batchc.cybergo.CyberGoRules.FREE_MAX_MULTIPLIER;
import static com.cpgame.batchc.cybergo.CyberGoRules.FREE_MULTIPLIER_STEP;
import static com.cpgame.batchc.cybergo.CyberGoRules.FREE_WILDS_PER_STEP;
import static com.cpgame.batchc.cybergo.CyberGoRules.MINIMUM_BET_LEVEL;
import static com.cpgame.batchc.cybergo.CyberGoRules.MINIMUM_BET_SIZE;
import static com.cpgame.batchc.cybergo.CyberGoRules.SCATTER;
import static com.cpgame.batchc.cybergo.CyberGoRules.WILD;
import static com.cpgame.batchc.cybergo.CyberGoRules.freeSpinsFor;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Constructed free-spin round. Paid start places 3/4/5 scatters (12/15/20 free games).
 * Free boards never contain scatter; wilds on reels 3/4 collect toward the x2..x20 meter.
 */
final class IndependentFree {
    private static final int SPECIAL_MIN_UNITS = 120;
    private static final int SPECIAL_MAX_UNITS = 9000;
    private static final List<List<String>> PAID_DEFAULTS = paidDefaults();

    private IndependentFree() { }

    record Plan(List<String> paid, List<List<String>> free) { }

    static Plan plan(RandomGenerator random) {
        int scatters = scatterCount(random);
        List<String> paid = paid(random, scatters);
        int spins = freeSpinsFor(ResultUtil.evaluate(paid, MINIMUM_BET_LEVEL, MINIMUM_BET_SIZE).scatterCount());
        if (spins < 12) {
            paid = paid(random, 3);
            spins = 12;
        }
        int wildStyle = random.nextInt(10);
        List<List<String>> free = new ArrayList<>(spins);
        for (int i = 0; i < spins; i++) free.add(freeBoard(random, wildStyle, i));
        int units = roundUnits(paid, free);
        for (int i = 0; i < free.size() && units < SPECIAL_MIN_UNITS; i++) {
            if (ResultUtil.evaluate(free.get(i), MINIMUM_BET_LEVEL, MINIMUM_BET_SIZE).baseWin().signum() > 0) continue;
            free.set(i, IndependentWin.threeKind(random, i % 2 == 0 ? "J" : "Q"));
            units = roundUnits(paid, free);
        }
        for (int i = 0; i < free.size() && units > SPECIAL_MAX_UNITS; i++) {
            free.set(i, IndependentLoss.payingBoard(random));
            units = roundUnits(paid, free);
        }
        if (units < SPECIAL_MIN_UNITS) {
            for (int i = 0; i < free.size(); i++) free.set(i, IndependentWin.threeKind(random, "J"));
        }
        return new Plan(paid, List.copyOf(free));
    }

    static List<String> paid(RandomGenerator random) {
        return paid(random, scatterCount(random));
    }

    static List<String> paid(RandomGenerator random, int scatters) {
        for (int attempt = 0; attempt < 5; attempt++) {
            List<String> board = paidCandidate(random, scatters);
            if (validPaid(board, scatters)) return board;
        }
        if (scatters == 3) return PAID_DEFAULTS.get(random.nextInt(10));
        List<String> fallback = paidCandidate(random, 3);
        return validPaid(fallback, 3) ? fallback : PAID_DEFAULTS.get(random.nextInt(10));
    }

    static List<String> free(RandomGenerator random) {
        return freeBoard(random, 4, 0);
    }

    static List<String> paidCandidate(RandomGenerator random, int scatters) {
        List<String> cells = new ArrayList<>(IndependentLoss.payingBoard(random));
        int count = Math.min(5, Math.max(3, scatters));
        int[] reels = {0, 1, 2, 3, 4};
        for (int i = reels.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = reels[i];
            reels[i] = reels[j];
            reels[j] = tmp;
        }
        for (int i = 0; i < count; i++) {
            int reel = reels[i];
            int row = random.nextInt(3);
            cells.set(reel * 3 + row, SCATTER);
        }
        return List.copyOf(cells);
    }

    static boolean validPaid(List<String> board, int scatters) {
        ResultUtil.Evaluation evaluation = ResultUtil.evaluate(board, MINIMUM_BET_LEVEL, MINIMUM_BET_SIZE);
        return evaluation.scatterCount() == scatters && evaluation.scatterCount() >= 3
                && evaluation.scatterCount() <= 5;
    }

    private static List<String> freeBoard(RandomGenerator random, int wildStyle, int index) {
        if (wildStyle >= 9) return lossWithWilds(random, 1 + index % 2);
        if (wildStyle >= 7 && index % 3 == 0) return lossWithWilds(random, 1);
        if (random.nextInt(10) < 8) return IndependentWin.threeKind(random, index % 2 == 0 ? "J" : "Q");
        return IndependentLoss.payingBoard(random);
    }

    private static List<String> lossWithWilds(RandomGenerator random, int wilds) {
        List<String> cells = new ArrayList<>(IndependentLoss.payingBoard(random));
        int count = Math.min(2, Math.max(1, wilds));
        int[] reels = {2, 3};
        if (count == 1) reels = new int[]{random.nextBoolean() ? 2 : 3};
        for (int reel : reels) {
            int row = random.nextInt(3);
            if (SCATTER.equals(cells.get(reel * 3 + row))) row = (row + 1) % 3;
            cells.set(reel * 3 + row, WILD);
        }
        List<String> board = List.copyOf(cells);
        if (ResultUtil.evaluate(board, MINIMUM_BET_LEVEL, MINIMUM_BET_SIZE).scatterCount() != 0) {
            cells = new ArrayList<>(IndependentLoss.payingBoard(random));
            for (int reel : reels) cells.set(reel * 3 + random.nextInt(3), WILD);
            board = List.copyOf(cells);
        }
        return board;
    }

    private static int scatterCount(RandomGenerator random) {
        int ticket = random.nextInt(100);
        if (ticket < 82) return 3;
        if (ticket < 97) return 4;
        return 5;
    }

    static int roundUnits(List<String> paid, List<List<String>> free) {
        ResultUtil.Evaluation paidEval = ResultUtil.evaluate(paid, MINIMUM_BET_LEVEL, MINIMUM_BET_SIZE);
        int total = units(paidEval);
        int multiplier = FREE_INITIAL_MULTIPLIER;
        int wilds = 0;
        for (List<String> board : free) {
            ResultUtil.Evaluation evaluation = ResultUtil.evaluate(board, MINIMUM_BET_LEVEL, MINIMUM_BET_SIZE);
            if (evaluation.scatterCount() != 0) return Integer.MAX_VALUE;
            wilds += evaluation.wildCount();
            while (wilds >= FREE_WILDS_PER_STEP && multiplier < FREE_MAX_MULTIPLIER) {
                multiplier = Math.min(FREE_MAX_MULTIPLIER, multiplier + FREE_MULTIPLIER_STEP);
                wilds -= FREE_WILDS_PER_STEP;
            }
            total += units(evaluation) * multiplier;
        }
        return total;
    }

    private static int units(ResultUtil.Evaluation evaluation) {
        if (evaluation.baseWin().signum() == 0) return 0;
        return evaluation.baseWin().divide(MINIMUM_BET_SIZE, 0, RoundingMode.UNNECESSARY).intValueExact();
    }

    private static List<List<String>> paidDefaults() {
        java.security.SecureRandom random = new java.security.SecureRandom();
        List<List<String>> defaults = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
            List<String> chosen = null;
            for (int n = 0; n < 40; n++) {
                List<String> board = paidCandidate(random, 3);
                if (validPaid(board, 3)) {
                    chosen = board;
                    break;
                }
            }
            if (chosen == null) throw new ExceptionInInitializerError("invalid constructed free trigger");
            defaults.add(chosen);
        }
        return List.copyOf(defaults);
    }
}
