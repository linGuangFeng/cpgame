package com.cpgame.fishinggo.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Empirical counts from 1241 captured origin rounds. */
public record DealingWeights(Map<String, Integer> paid,
                             Map<String, Integer> free,
                             Map<String, Integer> entryFill,
                             Map<Integer, Integer> entryScatter) {
    public static final List<String> FILL = List.of("A", "J", "K", "Q", "S1", "S2", "S3", "S4", "WILD");

    public DealingWeights {
        paid = validate(paid, ProtocolConstants.ORDER, "paid");
        free = validate(free, ProtocolConstants.ORDER, "free");
        entryFill = validate(entryFill, FILL, "entry-fill");
        entryScatter = validate(entryScatter, List.of(5, 6, 7), "entry-scatter");
    }

    public static DealingWeights empiricalDefaults() {
        return new DealingWeights(
                values(ProtocolConstants.ORDER, 1945, 1977, 2008, 1996, 2462, 2335, 2098, 2160, 1119, 515),
                values(ProtocolConstants.ORDER, 822, 824, 898, 877, 856, 859, 865, 862, 392, 125),
                values(FILL, 47, 45, 50, 43, 46, 49, 52, 52, 6),
                values(List.of(5, 6, 7), 25, 12, 4));
    }

    private static <T> Map<T, Integer> values(List<T> keys, int... counts) {
        Map<T, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) result.put(keys.get(i), counts[i]);
        return result;
    }

    private static <T> Map<T, Integer> validate(Map<T, Integer> source, List<T> keys, String mode) {
        if (source == null || !source.keySet().equals(new java.util.LinkedHashSet<>(keys)))
            throw new IllegalArgumentException(mode + " weights must contain exactly the confirmed outcomes");
        Map<T, Integer> result = new LinkedHashMap<>();
        for (T key : keys) {
            Integer value = source.get(key);
            if (value == null || value <= 0) throw new IllegalArgumentException(mode + " weight must be positive: " + key);
            result.put(key, value);
        }
        return Map.copyOf(result);
    }
}
