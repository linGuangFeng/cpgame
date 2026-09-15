package com.cpgame.curupira.model;

import java.util.List;

/** ResultUtil 从最小事实独立反推的结果池、实际倍率和各 Delivery。 */
public record RoundAnalysis(String resultPool, int actualMultiplier, List<EvaluatedBoard> deliveries) {
    public RoundAnalysis {
        deliveries = List.copyOf(deliveries);
        if (resultPool == null || resultPool.isBlank() || actualMultiplier < 0 || deliveries.isEmpty()) {
            throw new IllegalArgumentException("Invalid Round analysis");
        }
    }
}
