package com.cpgame.crazypiggy.generator.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** ResultUtil 从最小事实独立反推的全部派生结果。 */
public record ResultAnalysis(
        RoundMode mode,
        BigDecimal betAmount,
        Map<Integer, String> lineWins,
        BigDecimal baseAward,
        BigDecimal wheelAward,
        BigDecimal totalAward,
        int gameMode,
        int smallGameType,
        List<WheelDelivery> deliveries) {

    public ResultAnalysis {
        lineWins = Map.copyOf(lineWins);
        deliveries = List.copyOf(deliveries);
    }
}
