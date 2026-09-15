package com.cpgame.luckydragon.core;

import java.math.BigDecimal;
import java.util.Objects;

public record RoundRequest(BigDecimal betSize, int betLevel) {
    public RoundRequest {
        Objects.requireNonNull(betSize, "betSize");
        if (betSize.signum() <= 0) throw new IllegalArgumentException("betSize must be positive");
        if (betLevel < 1 || betLevel > 10) throw new IllegalArgumentException("betLevel must be 1..10");
    }

    public BigDecimal paidBet() {
        return betSize.multiply(BigDecimal.valueOf(betLevel));
    }
}
