package com.cpgame.luckycatii;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.random.RandomGenerator;

/**
 * Per-reel 3-cell shapes. AAB=top pair, BAA=bottom pair, AAA=three same, ABC=three different.
 * Separate from symbol weights.
 */
public record ReelPatterns(int aab, int baa, int aaa, int abc) {
    public enum Shape { AAB, BAA, AAA, ABC }

    public static final String KEY_PREFIX = "generation.reel-pattern.";
    public static final String KEY_SUFFIX = ".weight";

    public ReelPatterns {
        if (aab < 1 || baa < 1 || aaa < 1 || abc < 1)
            throw new IllegalArgumentException("reel-pattern weights must be positive");
    }

    public static ReelPatterns defaults() {
        return new ReelPatterns(2, 2, 2, 1);
    }

    public static ReelPatterns fromProperties(Properties p) {
        return new ReelPatterns(
                read(p, Shape.AAB), read(p, Shape.BAA), read(p, Shape.AAA), read(p, Shape.ABC));
    }

    public static String key(Shape shape) {
        return KEY_PREFIX + shape.name() + KEY_SUFFIX;
    }

    public Map<Shape, Integer> asMap() {
        Map<Shape, Integer> result = new LinkedHashMap<>();
        result.put(Shape.AAB, aab);
        result.put(Shape.BAA, baa);
        result.put(Shape.AAA, aaa);
        result.put(Shape.ABC, abc);
        return result;
    }

    public int weight(Shape shape) {
        return switch (shape) {
            case AAB -> aab;
            case BAA -> baa;
            case AAA -> aaa;
            case ABC -> abc;
        };
    }

    public Shape pick(RandomGenerator random) {
        int ticket = random.nextInt(aab + baa + aaa + abc);
        if ((ticket -= aab) < 0) return Shape.AAB;
        if ((ticket -= baa) < 0) return Shape.BAA;
        if ((ticket -= aaa) < 0) return Shape.AAA;
        return Shape.ABC;
    }

    public static Shape classify(String top, String mid, String bot) {
        if (top.equals(mid) && mid.equals(bot)) return Shape.AAA;
        if (top.equals(mid)) return Shape.AAB;
        if (mid.equals(bot)) return Shape.BAA;
        return Shape.ABC;
    }

    public static Shape classifyReel(java.util.List<String> board, int reel) {
        return classify(
                board.get(GameRules.cellIndex(reel, 0)),
                board.get(GameRules.cellIndex(reel, 1)),
                board.get(GameRules.cellIndex(reel, 2)));
    }

    private static int read(Properties p, Shape shape) {
        String key = key(shape);
        String raw = p.getProperty(key);
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("缺少配置: " + key);
        int value;
        try { value = Integer.parseInt(raw.trim()); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(key + " 必须为整数", ex); }
        if (value < 1) throw new IllegalArgumentException(key + " 必须为正整数");
        return value;
    }
}
