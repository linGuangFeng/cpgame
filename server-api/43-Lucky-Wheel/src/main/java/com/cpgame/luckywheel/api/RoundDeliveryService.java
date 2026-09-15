package com.cpgame.luckywheel.api;

import com.cpgame.luckywheel.core.GameRound;
import com.cpgame.luckywheel.core.RoundDelivery;

public final class RoundDeliveryService {
    public RoundDelivery claimNext(SessionState session, GameRound round) {
        Integer delivered = session.claimedRoundDelivery().get(round.roundKey());
        int next = delivered == null ? 0 : delivered + 1;
        if (next >= round.deliveries().size())
            throw new IllegalStateException("Round已完整领取，禁止重复派奖: " + round.roundKey());
        RoundDelivery delivery = round.deliveries().get(next);
        if (delivery.deliveryIndex() != next) throw new IllegalStateException("Delivery顺序损坏");
        session.claimedRoundDelivery().put(round.roundKey(), next);
        return delivery;
    }
}
