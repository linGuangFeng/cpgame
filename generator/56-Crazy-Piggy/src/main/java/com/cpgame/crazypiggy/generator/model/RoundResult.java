package com.cpgame.crazypiggy.generator.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record RoundResult(
        String roundKey,
        long createdAtEpochSecond,
        BigDecimal betSize,
        int betLevel,
        BigDecimal betAmount,
        List<String> symbols,
        Map<Integer, String> lineWins,
        BigDecimal baseAward,
        BigDecimal wheelAward,
        BigDecimal totalAward,
        int gameMode,
        int smallGameType,
        List<Integer> wheelPositions,
        List<Integer> wheelMultipliers,
        List<WheelDelivery> deliveries) {

    public boolean loss() { return totalAward.signum() == 0; }
    public boolean boosterWheel() { return gameMode == 1; }
}
