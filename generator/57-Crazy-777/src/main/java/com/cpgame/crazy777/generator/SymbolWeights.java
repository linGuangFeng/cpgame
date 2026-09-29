package com.cpgame.crazy777.generator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Marginal counts used to calibrate complete captured round kernels. */
public record SymbolWeights(Map<String, Integer> normal,
                            Map<String, Integer> entry,
                            Map<String, Integer> free) {
    public static final List<String> ALL = List.of("BLANK", "H1", "H2", "H3", "H4", "H5", "H6", "SC", "WILD");
    public static final List<String> FREE = List.of("BLANK", "H1", "H2", "H3", "H4", "H5", "H6", "WILD");

    public SymbolWeights {
        normal = validate(normal, ALL, "normal");
        entry = validate(entry, ALL, "entry");
        free = validate(free, FREE, "free");
    }

    public static SymbolWeights empiricalDefaults() {
        return new SymbolWeights(
                counts(ALL, 8848, 1583, 1182, 1157, 1206, 1197, 1200, 1062, 475),
                counts(ALL, 307, 22, 28, 32, 29, 24, 28, 126, 4),
                counts(FREE, 3030, 548, 453, 487, 440, 475, 426, 141));
    }

    private static Map<String, Integer> counts(List<String> symbols, int... values) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < symbols.size(); i++) result.put(symbols.get(i), values[i]);
        return result;
    }

    private static Map<String, Integer> validate(Map<String, Integer> source, List<String> symbols, String mode) {
        if (source == null || !source.keySet().equals(new java.util.LinkedHashSet<>(symbols)))
            throw new IllegalArgumentException(mode + " weights must contain exactly the confirmed symbols");
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String symbol : symbols) {
            Integer value = source.get(symbol);
            if (value == null || value <= 0) throw new IllegalArgumentException(mode + " weight must be positive: " + symbol);
            result.put(symbol, value);
        }
        return Map.copyOf(result);
    }
}
