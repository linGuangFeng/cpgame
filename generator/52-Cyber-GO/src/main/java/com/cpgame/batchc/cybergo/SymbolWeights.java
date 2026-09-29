package com.cpgame.batchc.cybergo;

import static com.cpgame.batchc.cybergo.CyberGoRules.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** Per-symbol dealing weights. Numbers may follow capture frequencies; dealing itself uses rule caps. */
public record SymbolWeights(Map<String, Integer> normal, Map<String, Integer> free) {
    public SymbolWeights {
        normal = Map.copyOf(validate(normal, true, "普通"));
        free = Map.copyOf(validate(free, false, "免费"));
    }

    public static SymbolWeights localDefaults() {
        Map<String, Integer> normal = new LinkedHashMap<>();
        normal.put("S1", 3099); normal.put("S2", 2758); normal.put("S3", 2514); normal.put("S4", 2310);
        normal.put("A", 2098); normal.put("K", 2132); normal.put("Q", 2223); normal.put("J", 2081);
        normal.put(WILD, 724); normal.put(SCATTER, 491);
        Map<String, Integer> free = new LinkedHashMap<>();
        free.put("S1", 1810); free.put("S2", 1654); free.put("S3", 1732); free.put("S4", 1764);
        free.put("A", 478); free.put("K", 505); free.put("Q", 430); free.put("J", 445);
        free.put(WILD, 452); // Captured free transitions contain no Scatter.
        return new SymbolWeights(normal, free);
    }

    private static Map<String, Integer> validate(Map<String, Integer> source, boolean scatterRequired, String mode) {
        if (source == null) throw new IllegalArgumentException(mode + "符号权重不能为空");
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String symbol : PAYING_SYMBOLS) requirePositive(source, result, symbol, mode);
        requirePositive(source, result, WILD, mode);
        if (scatterRequired) requirePositive(source, result, SCATTER, mode);
        else if (source.containsKey(SCATTER)) throw new IllegalArgumentException("免费权重不得包含Scatter");
        if (source.size() != result.size()) throw new IllegalArgumentException(mode + "权重包含未确认符号");
        return result;
    }

    private static void requirePositive(Map<String, Integer> source, Map<String, Integer> target,
                                        String symbol, String mode) {
        Integer value = source.get(symbol);
        if (value == null || value <= 0) throw new IllegalArgumentException(mode + "符号" + symbol + "权重必须为正数");
        target.put(symbol, value);
    }
}
