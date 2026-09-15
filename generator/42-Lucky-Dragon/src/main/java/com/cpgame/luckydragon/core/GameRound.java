package com.cpgame.luckydragon.core;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record GameRound(
    String roundKey,
    String transferId,
    RoundRequest request,
    SpinResult result,
    BigDecimal balanceAfter,
    Instant createdAt,
    int deliveryIndex,
    boolean terminal
) {
    public GameRound {
        Objects.requireNonNull(roundKey, "roundKey");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(balanceAfter, "balanceAfter");
        Objects.requireNonNull(createdAt, "createdAt");
        if (deliveryIndex != 0) throw new IllegalArgumentException("gid42 has exactly one delivery at index 0");
        if (!terminal) throw new IllegalArgumentException("gid42 observed rounds are terminal after one paid spin");
    }
}
