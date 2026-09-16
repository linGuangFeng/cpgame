package com.cpgame.demo.redis;

import java.util.List;
import java.util.function.IntFunction;
import java.util.random.RandomGenerator;

/** Target multiplier -> nearest indexed nonempty bucket at or below it. */
public final class RedisFloorLookup {
    @FunctionalInterface public interface Command<E extends Exception> { Object call(String... args) throws E; }
    private RedisFloorLookup() { }

    public static <E extends Exception> Cursor<E> open(Command<E> redis, String index,
            IntFunction<String> listKey, RandomGenerator random, int minimum, int maximum) throws E {
        if (minimum < 0 || maximum < minimum) throw new IllegalArgumentException("invalid multiplier range");
        List<?> highest = list(redis.call("ZREVRANGEBYSCORE", index, Integer.toString(maximum),
                Integer.toString(minimum), "LIMIT", "0", "1"));
        if (highest.isEmpty()) return new Cursor<>(redis, index, listKey, minimum, null);
        int top = integer(highest.get(0));
        if (top < minimum || top > maximum) throw new IllegalStateException("Redis multiplier index/range mismatch");
        int target = (int) random.nextLong(minimum, (long) top + 1);
        return new Cursor<>(redis, index, listKey, minimum, Integer.toString(target));
    }

    public static <E extends Exception> Integer choose(Command<E> redis, String index,
            IntFunction<String> listKey, RandomGenerator random, int minimum, int maximum) throws E {
        return open(redis, index, listKey, random, minimum, maximum).next();
    }

    public static final class Cursor<E extends Exception> {
        private final Command<E> redis;
        private final String index;
        private final IntFunction<String> listKey;
        private final int minimum;
        private String upper;
        private Cursor(Command<E> redis, String index, IntFunction<String> listKey, int minimum, String upper) {
            this.redis = redis; this.index = index; this.listKey = listKey; this.minimum = minimum; this.upper = upper;
        }
        /** Call again only when a chosen bucket/member cannot satisfy this game's mode. */
        public Integer next() throws E {
            while (upper != null) {
                List<?> found = list(redis.call("ZREVRANGEBYSCORE", index, upper,
                        Integer.toString(minimum), "LIMIT", "0", "1"));
                if (found.isEmpty()) { upper = null; return null; }
                int ratio = integer(found.get(0));
                boolean exclusive = upper.charAt(0) == '(';
                int ceiling = Integer.parseInt(exclusive ? upper.substring(1) : upper);
                if (ratio < minimum || ratio > ceiling || (exclusive && ratio == ceiling))
                    throw new IllegalStateException("Redis multiplier index is not descending");
                upper = "(" + ratio;
                Object length = redis.call("LLEN", listKey.apply(ratio));
                if (Long.parseLong(length.toString()) > 0) return ratio;
            }
            return null;
        }
    }
    private static List<?> list(Object value) { return value instanceof List<?> values ? values : List.of(); }
    private static int integer(Object value) { return Integer.parseInt(value.toString()); }
}
