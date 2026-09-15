package com.cpgame.batcha.g16;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Complete paid Round, including every Mary/free/cascade Delivery until legal termination. */
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
        if (rawGameId != GameRuleCore.RAW_GAME_ID) throw new IllegalArgumentException("only raw gid 16 is supported");
        mode = Objects.requireNonNull(mode, "mode");
        paidBet = Objects.requireNonNull(paidBet, "paidBet").stripTrailingZeros();
        betSize = Objects.requireNonNull(betSize, "betSize").stripTrailingZeros();
        if (paidBet.signum() <= 0 || betSize.signum() <= 0 || betLevel < 1) {
            throw new IllegalArgumentException("bet values must be positive");
        }
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (steps.isEmpty()) throw new IllegalArgumentException("complete Round needs at least one Step");
        payout = Objects.requireNonNull(payout, "payout").stripTrailingZeros();
        multiplier = Objects.requireNonNull(multiplier, "multiplier").stripTrailingZeros();
    }
}
