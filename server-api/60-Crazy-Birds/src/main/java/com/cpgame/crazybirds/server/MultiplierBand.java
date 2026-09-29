package com.cpgame.crazybirds.server;

import java.util.random.RandomGenerator;

/** 六档实际倍率按 Crazy Birds 的缓存 scale=100 换算为整数目标区间。 */
public enum MultiplierBand {
    ZERO(0, 0, "0"),
    UP_TO_5(1, 500, "0-5"),
    FIVE_TO_20(501, 2_000, "5-20"),
    TWENTY_TO_50(2_001, 5_000, "20-50"),
    FIFTY_TO_100(5_001, 10_000, "50-100"),
    HUNDRED_TO_10000(10_001, 1_000_000, "100-10000");

    private final int minimum;
    private final int maximum;
    private final String propertySuffix;

    MultiplierBand(int minimum, int maximum, String propertySuffix) {
        this.minimum = minimum;
        this.maximum = maximum;
        this.propertySuffix = propertySuffix;
    }

    public int target(RandomGenerator random) {
        if (minimum == maximum) return minimum;
        return (int) random.nextLong(minimum, (long) maximum + 1);
    }

    public int minimum() { return minimum; }

    public int maximum() { return maximum; }

    public String propertySuffix() { return propertySuffix; }
}
