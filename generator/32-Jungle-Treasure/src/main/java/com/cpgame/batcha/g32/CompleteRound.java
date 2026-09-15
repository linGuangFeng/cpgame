package com.cpgame.batcha.g32;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record CompleteRound(
        int rawGameId,
        RoundMode mode,
        BigDecimal paidBet,
        BigDecimal betSize,
        int betLevel,
        List<Step> steps,
        BigDecimal payout,
        BigDecimal multiplier) {

    public CompleteRound {
        if (rawGameId != GameRuleCore.RAW_GAME_ID) throw new IllegalArgumentException("only raw gid 32 is supported");
        mode = Objects.requireNonNull(mode);
        paidBet = Objects.requireNonNull(paidBet).stripTrailingZeros();
        betSize = Objects.requireNonNull(betSize).stripTrailingZeros();
        steps = List.copyOf(Objects.requireNonNull(steps));
        if (steps.isEmpty()) throw new IllegalArgumentException("complete Round needs at least one Step");
        payout = Objects.requireNonNull(payout).stripTrailingZeros();
        multiplier = Objects.requireNonNull(multiplier).stripTrailingZeros();
    }
}
