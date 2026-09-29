package com.cpgame.crazybirds.generator;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 家族默认 -> 类型 -> 具体倍率桶的容量继承。 */
public final class RetentionPolicy {
    private static final Pattern TYPE = Pattern.compile(
            "retention\\.(normal|mary)\\.type\\.(\\d+)-per-multiplier");
    private static final Pattern UNIT = Pattern.compile(
            "retention\\.(normal|mary)\\.type\\.(\\d+)\\.units\\.(\\d+)");

    private final int normalDefault;
    private final int maryDefault;
    private final Map<Key, Integer> typeCaps = new HashMap<>();
    private final Map<BucketKey, Integer> bucketCaps = new HashMap<>();

    public RetentionPolicy(Properties properties) {
        normalDefault = positive(properties, "retention.normal-per-multiplier");
        maryDefault = positive(properties, "retention.mary-per-multiplier");
        for (String name : properties.stringPropertyNames()) {
            Matcher type = TYPE.matcher(name);
            Matcher unit = UNIT.matcher(name);
            if (type.matches()) {
                typeCaps.put(new Key("mary".equals(type.group(1)), Integer.parseInt(type.group(2))),
                        positive(properties, name));
            } else if (unit.matches()) {
                bucketCaps.put(new BucketKey("mary".equals(unit.group(1)), Integer.parseInt(unit.group(2)),
                                Integer.parseInt(unit.group(3))), positive(properties, name));
            }
        }
    }

    public int capacity(boolean mary, int type, int multiplier) {
        Integer bucket = bucketCaps.get(new BucketKey(mary, type, multiplier));
        if (bucket != null) return bucket;
        Integer byType = typeCaps.get(new Key(mary, type));
        if (byType != null) return byType;
        return mary ? maryDefault : normalDefault;
    }

    public static boolean recognizes(String key) {
        return "retention.normal-per-multiplier".equals(key)
                || "retention.mary-per-multiplier".equals(key)
                || TYPE.matcher(key).matches() || UNIT.matcher(key).matches();
    }

    private static int positive(Properties p, String key) {
        String raw = p.getProperty(key);
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("缺少配置: " + key);
        int value;
        try { value = Integer.parseInt(raw.trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key + " 必须为正整数", e); }
        if (value <= 0) throw new IllegalArgumentException(key + " 必须为正整数");
        return value;
    }

    private record Key(boolean mary, int type) { }
    private record BucketKey(boolean mary, int type, int multiplier) { }
}
