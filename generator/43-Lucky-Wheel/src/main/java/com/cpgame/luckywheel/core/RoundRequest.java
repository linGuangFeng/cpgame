package com.cpgame.luckywheel.core;

import java.math.BigDecimal;

public record RoundRequest(int betLevel, int betSize, BigDecimal balanceBefore) {
    public RoundRequest {
        if (betLevel < 1 || betSize != 1) {
            throw new IllegalArgumentException("只允许正整数 bl 与bs=1");
        }
        BigDecimal bet = BigDecimal.valueOf((long) betLevel * betSize);
        if (balanceBefore == null || balanceBefore.compareTo(bet) < 0) {
            throw new IllegalArgumentException("余额不足");
        }
    }

    public int betProfile() { return betLevel < 5 ? 1 : 5; }
}
