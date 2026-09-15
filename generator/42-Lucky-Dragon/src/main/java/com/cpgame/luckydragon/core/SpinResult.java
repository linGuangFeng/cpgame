package com.cpgame.luckydragon.core;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record SpinResult(
    List<String> symbols,
    String winningSymbol,
    int reelMultiplier,
    BigDecimal payout,
    OutcomeType outcome
) {
    public SpinResult {
        symbols = List.copyOf(Objects.requireNonNull(symbols, "symbols"));
        if (symbols.size() != 3) throw new IllegalArgumentException("Lucky Dragon requires three symbols");
        Objects.requireNonNull(winningSymbol, "winningSymbol");
        Objects.requireNonNull(payout, "payout");
        Objects.requireNonNull(outcome, "outcome");
        if (payout.signum() < 0) throw new IllegalArgumentException("payout must not be negative");
    }

    public boolean terminal() { return true; }
}
