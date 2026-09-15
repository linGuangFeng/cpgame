package com.cpgame.luckynightmarket;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Generation-side judgement. One unit of payout is b*l; a paid bet is five units. */
public final class GameRuleCore {
    public static final int BET_BASE = 5;
    public static final int FEATURE_STEPS = 8;
    public static final int MAX_TOTAL_BET_MULTIPLIER = 3200;
    private static final int[][] PAYLINES = {
            {1, 4, 7}, {0, 3, 6}, {2, 5, 8}, {0, 4, 8}, {2, 4, 6}
    };
    private static final int[] ODDS = {100, 50, 25, 10, 5, 3, 2};

    private GameRuleCore() {}

    public record Win(int c, int l, int o, int s) {}

    /** lineUnits already includes the applied multiplier. No currency rounding occurs here. */
    public record Evaluation(List<Win> wins, int appliedMultiplier, long lineUnits, long wheelUnits, long units) {
        public Evaluation { wins = List.copyOf(wins); }
    }

    public static Evaluation evaluate(RoundFact.Step step, boolean feature) {
        Objects.requireNonNull(step, "step");
        if (feature && step.wheel()) throw new IllegalArgumentException("Wheel is not a feature step");
        if (step.wheel() != (!feature && step.muls().get(1) == 0)) {
            throw new IllegalArgumentException("Center ticket and wheel outcome disagree");
        }
        List<Win> wins = new ArrayList<>();
        long lineOdds = 0;
        for (int line = 0; line < PAYLINES.length; line++) {
            int matchedSymbol = 0;
            boolean matches = true;
            for (int position : PAYLINES[line]) {
                int symbol = step.ps().get(position);
                if (symbol == 0) continue;
                if (matchedSymbol == 0) matchedSymbol = symbol;
                else if (matchedSymbol != symbol) { matches = false; break; }
            }
            if (matches) {
                int odds = ODDS[matchedSymbol];
                wins.add(new Win(3, line + 1, odds, matchedSymbol));
                lineOdds += odds;
            }
        }
        int appliedMultiplier = feature
                ? step.muls().stream().mapToInt(Integer::intValue).sum()
                : step.muls().get(1);
        if (appliedMultiplier == 0) appliedMultiplier = 1;
        long lineUnits = Math.multiplyExact(lineOdds, appliedMultiplier);
        long wheelUnits = Math.multiplyExact((long) BET_BASE, step.wheelMultiplier());
        return new Evaluation(wins, appliedMultiplier, lineUnits, wheelUnits, Math.addExact(lineUnits, wheelUnits));
    }

    public static long totalUnits(RoundFact round) {
        Objects.requireNonNull(round, "round");
        long result = 0;
        for (RoundFact.Step step : round.steps()) result = Math.addExact(result, evaluate(step, round.feature()).units());
        return result;
    }

    /** Rejects inconsistent facts, without altering boards or inventing a clipped cash result. */
    public static void validate(RoundFact round) {
        long total = totalUnits(round);
        switch (round.mode()) {
            case ORDINARY_LOSS -> {
                if (round.steps().get(0).wheel() || total != 0) throw new IllegalArgumentException("Loss mode is not a loss");
            }
            case ORDINARY_WIN -> {
                if (round.steps().get(0).wheel() || total <= 0) throw new IllegalArgumentException("Ordinary win mode has no line win");
            }
            case LUCKY_WHEEL -> {
                if (!round.steps().get(0).wheel()) throw new IllegalArgumentException("Wheel mode has no wheel outcome");
            }
            case LUCKY_FEATURE -> {
                if (evaluate(round.steps().get(0), true).units() != 0) {
                    throw new IllegalArgumentException("Feature start must be an evidenced matrix loss");
                }
            }
        }
        if (total > (long) MAX_TOTAL_BET_MULTIPLIER * BET_BASE) {
            throw new IllegalArgumentException("Round exceeds advertised 3200x total-bet cap");
        }
    }
}
