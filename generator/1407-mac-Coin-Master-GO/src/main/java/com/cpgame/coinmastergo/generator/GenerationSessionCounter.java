package com.cpgame.coinmastergo.generator;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/** Counts only members accepted by this process, never pre-existing Redis length. */
public final class GenerationSessionCounter {
    private final int maximum;
    private final Map<Bucket, Integer> accepted = new HashMap<>();

    public GenerationSessionCounter(int maximum) {
        if (maximum <= 0) throw new IllegalArgumentException("maximum must be positive");
        this.maximum = maximum;
    }

    public boolean tryAccept(RoundResultUtil.RoundAnalysis result) {
        if (result.multiplier().signum() == 0) return false;
        Bucket bucket = new Bucket(result.pool(), result.multiplier().stripTrailingZeros());
        int count = accepted.getOrDefault(bucket, 0);
        if (count >= maximum) return false;
        accepted.put(bucket, count + 1);
        return true;
    }

    public int count(RoundResultUtil.ResultPool pool, BigDecimal multiplier) {
        return accepted.getOrDefault(new Bucket(pool, multiplier.stripTrailingZeros()), 0);
    }

    private record Bucket(RoundResultUtil.ResultPool pool, BigDecimal multiplier) { }
}
