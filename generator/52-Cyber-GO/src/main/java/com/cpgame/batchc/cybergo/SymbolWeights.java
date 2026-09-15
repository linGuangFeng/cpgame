package com.cpgame.batchc.cybergo;

import static com.cpgame.batchc.cybergo.CyberGoRules.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** Cyber GO 本地复刻候选分布；所有权重为正数，不声明或冒充原厂 RTP。 */
public record SymbolWeights(Map<String, Integer> normal, Map<String, Integer> free) {
    public SymbolWeights {
        normal = Map.copyOf(validate(normal, true, "普通"));
        free = Map.copyOf(validate(free, false, "免费"));
    }

    public static SymbolWeights localDefaults() {
        Map<String, Integer> normal = new LinkedHashMap<>();
        normal.put("S1", 5); normal.put("S2", 6); normal.put("S3", 7); normal.put("S4", 8);
        normal.put("A", 10); normal.put("K", 10); normal.put("Q", 12); normal.put("J", 12);
        normal.put(WILD, 2); normal.put(SCATTER, 3);
        Map<String, Integer> free = new LinkedHashMap<>(normal);
        free.remove(SCATTER); // B04 已确认免费盘面不出现 Scatter。
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
