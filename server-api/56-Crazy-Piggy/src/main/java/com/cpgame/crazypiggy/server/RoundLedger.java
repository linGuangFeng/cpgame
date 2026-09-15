package com.cpgame.crazypiggy.server;

import com.cpgame.crazypiggy.generator.model.RoundResult;
import com.cpgame.crazypiggy.generator.model.WheelDelivery;

import java.util.Optional;

/** 一个完整 Round 只能领取一次，后续投影严格按 deliveryIndex 前进。 */
public final class RoundLedger {
    private final RoundResult round;
    private boolean claimed;
    private int deliveryIndex;

    public RoundLedger(RoundResult round) { this.round = round; }

    public synchronized RoundResult claim() {
        if (claimed) throw new IllegalStateException("roundKey 已领取: " + round.roundKey());
        claimed = true;
        return round;
    }

    public synchronized Optional<WheelDelivery> nextDelivery() {
        if (!claimed) throw new IllegalStateException("必须先领取完整 Round");
        if (deliveryIndex >= round.deliveries().size()) return Optional.empty();
        WheelDelivery delivery = round.deliveries().get(deliveryIndex);
        if (delivery.deliveryIndex() != deliveryIndex) throw new IllegalStateException("Delivery 序号不连续");
        deliveryIndex++;
        return Optional.of(delivery);
    }

    public String roundKey() { return round.roundKey(); }
    public synchronized int deliveryIndex() { return deliveryIndex; }
    public synchronized boolean claimed() { return claimed; }
}
