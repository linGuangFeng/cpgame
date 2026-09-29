package com.cpgame.luckywheel.core;

import java.math.BigDecimal;

public record RoundRequest(int betLevel, int betSize, BigDecimal balanceBefore) {
    public RoundRequest {
        if (balanceBefore == null || balanceBefore.compareTo(LuckyWheelStake.ba(betLevel)) < 0) {
            throw new IllegalArgumentException("余额不足");
        }
    }

    public int betProfile() { return betLevel < 5 ? 1 : 5; }
}
