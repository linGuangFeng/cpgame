package com.cpgame.batcha.g16;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** One paying symbol's complete anywhere-on-board match, using column*10+row wire positions. */
public record WinMatch(String symbolKey, List<Integer> indices, BigDecimal winAmount) {
    public WinMatch {
        Objects.requireNonNull(symbolKey, "symbolKey");
        indices = List.copyOf(Objects.requireNonNull(indices, "indices"));
        winAmount = Objects.requireNonNull(winAmount, "winAmount").stripTrailingZeros();
    }
}
