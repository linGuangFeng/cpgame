package com.cpgame.luckywheel.core;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record GameRound(
        String roundKey,
        OutcomeType outcome,
        Instant createdAt,
        List<RoundDelivery> deliveries
) implements Serializable {
    public GameRound {
        if (roundKey == null || roundKey.isBlank()) throw new IllegalArgumentException("roundKey 不能为空");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(createdAt, "createdAt");
        deliveries = List.copyOf(deliveries);
        if (deliveries.size() != 1 || deliveries.get(0).deliveryIndex() != 0) {
            throw new IllegalArgumentException("当前已确认分支必须恰好包含一个完整 Delivery");
        }
    }
}
