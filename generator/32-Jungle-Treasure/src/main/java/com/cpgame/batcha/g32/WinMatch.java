package com.cpgame.batcha.g32;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record WinMatch(String symbolKey, List<List<Integer>> reelGroups, int ways, BigDecimal winAmount) {
    public WinMatch {
        Objects.requireNonNull(symbolKey, "symbolKey");
        reelGroups = reelGroups.stream().map(List::copyOf).toList();
        winAmount = Objects.requireNonNull(winAmount, "winAmount").stripTrailingZeros();
    }
}
