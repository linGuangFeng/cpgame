package com.cpgame.luckycatii;

import java.util.LinkedHashMap;
import java.util.Map;

/** Per-symbol reference counts from captured boards. Dealing uses these as weights, not as copied layouts. */
public record SymbolWeights(Map<String, Integer> normal,
                            Map<String, Integer> special,
                            Map<String, Integer> respin) {
    public SymbolWeights {
        normal = validate(normal, "normal");
        special = validate(special, "special");
        respin = validate(respin, "respin");
    }

    public static SymbolWeights empiricalDefaults() {
        return new SymbolWeights(
                counts(1492, 1907, 1786, 1763, 1739, 1830, 1876),
                counts(336, 101, 81, 73, 99, 44, 58),
                counts(54, 82, 55, 60, 77, 59, 45));
    }

    private static Map<String, Integer> counts(int wild, int s1, int s2, int s3,
                                                int s4, int s5, int s6) {
        Map<String, Integer> result = new LinkedHashMap<>();
        int[] values = {wild, s1, s2, s3, s4, s5, s6};
        for (int i = 0; i < GameRules.SYMBOLS.size(); i++) result.put(GameRules.SYMBOLS.get(i), values[i]);
        return result;
    }

    private static Map<String, Integer> validate(Map<String, Integer> source, String mode) {
        if (source == null || !source.keySet().equals(new java.util.LinkedHashSet<>(GameRules.SYMBOLS)))
            throw new IllegalArgumentException(mode + " weights must contain exactly the confirmed symbols");
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String symbol : GameRules.SYMBOLS) {
            Integer value = source.get(symbol);
            if (value == null || value <= 0) throw new IllegalArgumentException(mode + " weight must be positive: " + symbol);
            result.put(symbol, value);
        }
        return Map.copyOf(result);
    }
}
