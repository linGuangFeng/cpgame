package com.cpgame.crazypiggy.generator;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Crazy Piggy 本地复刻生成权重。原厂服务端权重未知；默认值不代表原厂概率或 RTP。
 * 所有正式随机分支都通过本对象显式取权重，避免隐式均匀随机。
 */
public final class GenerationWeights {
    public static final String ORDINARY = "ORDINARY";
    public static final String BOOSTER_WHEEL = "BOOSTER_WHEEL";

    private final Map<String, Integer> modeWeights;
    private final Map<String, Integer> symbolWeights;
    private final Map<Integer, Integer> wheelStepWeights;
    private final Map<Integer, Integer> wheelPositionWeights;

    public GenerationWeights(Map<String, Integer> modeWeights,
                             Map<String, Integer> symbolWeights,
                             Map<Integer, Integer> wheelStepWeights,
                             Map<Integer, Integer> wheelPositionWeights) {
        this.modeWeights = validated(modeWeights, "模式", Map.of(ORDINARY, 1, BOOSTER_WHEEL, 1));
        this.symbolWeights = validated(symbolWeights, "符号",
                GameRules.SYMBOLS.stream().collect(LinkedHashMap::new, (m, s) -> m.put(s, 1), Map::putAll));
        this.wheelStepWeights = validated(wheelStepWeights, "轮盘派奖步数",
                orderedIntMap(new int[]{2, 3, 4, 5, 6}));
        this.wheelPositionWeights = validated(wheelPositionWeights, "轮盘位置",
                orderedIntMap(new int[]{0, 1, 2, 3, 4, 5, 6, 7}));
    }

    public static GenerationWeights defaults() {
        return new GenerationWeights(
                orderedStringMap(new String[]{ORDINARY, BOOSTER_WHEEL}, new int[]{98, 2}),
                orderedStringMap(new String[]{"HOT", "SEV", "H2", "H3", "H4", "H5", "H6", "H7"},
                        new int[]{2, 4, 6, 8, 12, 16, 22, 30}),
                orderedIntMap(new int[]{2, 3, 4, 5, 6}, new int[]{35, 25, 18, 13, 9}),
                orderedIntMap(new int[]{0, 1, 2, 3, 4, 5, 6, 7},
                        new int[]{20, 5, 16, 14, 12, 5, 10, 18}));
    }

    public Map<String, Integer> modeWeights() { return modeWeights; }
    public Map<String, Integer> symbolWeights() { return symbolWeights; }
    public Map<Integer, Integer> wheelStepWeights() { return wheelStepWeights; }
    public Map<Integer, Integer> wheelPositionWeights() { return wheelPositionWeights; }

    public <T> T choose(RandomGenerator random, Map<T, Integer> weights) {
        long total = 0;
        for (int weight : weights.values()) total = Math.addExact(total, weight);
        long point = random.nextLong(total);
        for (Map.Entry<T, Integer> entry : weights.entrySet()) {
            point -= entry.getValue();
            if (point < 0) return entry.getKey();
        }
        throw new IllegalStateException("带权随机未选中任何项目");
    }

    private static <T> Map<T, Integer> validated(Map<T, Integer> actual, String label, Map<T, Integer> required) {
        if (actual == null || !actual.keySet().equals(required.keySet()))
            throw new IllegalArgumentException(label + "权重项目必须完整且不得包含额外项");
        LinkedHashMap<T, Integer> copy = new LinkedHashMap<>();
        for (T key : required.keySet()) {
            Integer value = actual.get(key);
            if (value == null || value <= 0) throw new IllegalArgumentException(label + "权重必须全部为正数: " + key);
            copy.put(key, value);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<Integer, Integer> orderedIntMap(int[] keys) {
        int[] values = new int[keys.length];
        java.util.Arrays.fill(values, 1);
        return orderedIntMap(keys, values);
    }

    private static Map<Integer, Integer> orderedIntMap(int[] keys, int[] values) {
        LinkedHashMap<Integer, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < keys.length; i++) result.put(keys[i], values[i]);
        return result;
    }

    private static Map<String, Integer> orderedStringMap(String[] keys, int[] values) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < keys.length; i++) result.put(keys[i], values[i]);
        return result;
    }
}
