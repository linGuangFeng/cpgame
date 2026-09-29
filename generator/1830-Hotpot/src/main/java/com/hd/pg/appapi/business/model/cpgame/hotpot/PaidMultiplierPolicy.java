package com.hd.pg.appapi.business.model.cpgame.hotpot;

import java.util.Properties;
import java.util.Random;

/** Immutable second-stage weights, applied only after a paid multiplier opportunity. */
public final class PaidMultiplierPolicy {
    public static final String PREFIX = "generation.paid-multiplier.x";
    private static final int[] DEFAULTS = {5000, 2500, 1250, 750, 500};
    private final long[] cumulative;
    private final long total;

    private PaidMultiplierPolicy(int[] weights) {
        cumulative = new long[5];
        long sum = 0;
        for (int i = 0; i < 5; i++) {
            if (weights[i] < 0)
                throw new IllegalArgumentException(key(i + 1) + " must be a nonnegative integer");
            sum += weights[i];
            cumulative[i] = sum;
        }
        if (sum == 0) throw new IllegalArgumentException("generation.paid-multiplier x1..x5 weights cannot all be zero");
        total = sum;
    }

    public static String key(int multiplier) { return PREFIX + multiplier + "-weight"; }
    public static PaidMultiplierPolicy defaults() { return fromProperties(new Properties()); }
    public static PaidMultiplierPolicy fromProperties(Properties p) {
        int[] values = new int[5];
        for (int i = 0; i < 5; i++) {
            String key = key(i + 1);
            try { values[i] = Integer.parseInt(p.getProperty(key, Integer.toString(DEFAULTS[i])).trim()); }
            catch (NumberFormatException invalid) {
                throw new IllegalArgumentException(key + " must be an integer between 0 and 2147483647", invalid);
            }
        }
        return new PaidMultiplierPolicy(values);
    }

    public int choose(Random random) {
        double ticket = random.nextDouble() * total;
        for (int i = 0; i < 5; i++) if (ticket < cumulative[i]) return i + 1;
        throw new IllegalStateException("multiplier weight selection exhausted");
    }
}
