package com.cpgame.batcha.g8;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** One 4-connected cluster, using row*10+column wire positions. */
public record WinMatch(String symbolKey, List<Integer> indices, BigDecimal winAmount) {
    public WinMatch {
        Objects.requireNonNull(symbolKey, "symbolKey");
        indices = List.copyOf(Objects.requireNonNull(indices, "indices"));
        winAmount = Objects.requireNonNull(winAmount, "winAmount").stripTrailingZeros();
    }
}
