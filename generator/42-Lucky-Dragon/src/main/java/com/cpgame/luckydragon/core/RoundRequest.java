package com.cpgame.luckydragon.core;

import java.math.BigDecimal;
import java.util.Objects;

public record RoundRequest(BigDecimal betSize, int betLevel) {
    public RoundRequest {
        Objects.requireNonNull(betSize, "betSize");
    }

    public BigDecimal paidBet() {
        return betSize.multiply(BigDecimal.valueOf(betLevel));
    }
}
