package com.cpgame.luckynightmarket;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Independent settlement oracle. Does not call generation-side judgement. */
public final class ResultUtil {
    private ResultUtil() {}

    public static GameRuleCore.Evaluation evaluate(RoundFact.Step step, boolean feature) {
        Objects.requireNonNull(step, "step");
        if (feature && step.wheelMultiplier() != 0) throw new IllegalArgumentException("Feature cannot contain wheel prize");
        if (!feature && ((step.muls().get(1) == 0) != (step.wheelMultiplier() != 0))) {
            throw new IllegalArgumentException("Wheel prize must agree with center ticket");
        }
        // Derive the provider line geometry independently using bottom-origin rows.
        int[][] rowsByLine = {{1, 1, 1}, {0, 0, 0}, {2, 2, 2}, {0, 1, 2}, {2, 1, 0}};
        List<GameRuleCore.Win> wins = new ArrayList<>();
        long baseUnits = 0;
        for (int line = 0; line < rowsByLine.length; line++) {
            int mask = 0;
            for (int col = 0; col < 3; col++) {
                int symbol = step.ps().get(col * 3 + rowsByLine[line][col]);
                if (symbol != 0) mask |= 1 << symbol;
            }
            if (Integer.bitCount(mask) > 1) continue;
            int symbol = mask == 0 ? 0 : Integer.numberOfTrailingZeros(mask);
            int odds = switch (symbol) {
                case 0 -> 100;
                case 1 -> 50;
                case 2 -> 25;
                case 3 -> 10;
                case 4 -> 5;
                case 5 -> 3;
                case 6 -> 2;
                default -> throw new IllegalArgumentException("Invalid symbol");
            };
            baseUnits += odds;
            wins.add(new GameRuleCore.Win(3, line + 1, odds, symbol));
        }
        int multiplier = step.muls().get(1);
        if (feature) multiplier = step.muls().get(0) + step.muls().get(1) + step.muls().get(2);
        if (multiplier == 0) multiplier = 1;
        long lines = Math.multiplyExact(baseUnits, multiplier);
        long wheel = Math.multiplyExact(5L, step.wheelMultiplier());
        return new GameRuleCore.Evaluation(wins, multiplier, lines, wheel, Math.addExact(lines, wheel));
    }

    public static long totalUnits(RoundFact round) {
        long units = 0;
        for (RoundFact.Step step : round.steps()) units = Math.addExact(units, evaluate(step, round.feature()).units());
        return units;
    }

    public static BigDecimal stepCash(RoundFact.Step step, boolean feature, BigDecimal bet, int level) {
        if (bet == null || bet.signum() <= 0 || level <= 0) throw new IllegalArgumentException("Positive bet and level required");
        return bet.multiply(BigDecimal.valueOf(level)).multiply(BigDecimal.valueOf(evaluate(step, feature).units()));
    }

    public static BigDecimal totalCash(RoundFact round, BigDecimal bet, int level) {
        if (bet == null || bet.signum() <= 0 || level <= 0) throw new IllegalArgumentException("Positive bet and level required");
        return bet.multiply(BigDecimal.valueOf(level)).multiply(BigDecimal.valueOf(totalUnits(round)));
    }
}
