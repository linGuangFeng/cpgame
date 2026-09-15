package com.cpgame.batcha.g8;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** One protocol Delivery. Balances, sessions, clocks and HTTP envelopes are absent. */
public record Step(
        int deliveryIndex,
        BigDecimal betAmount,
        BigDecimal betSize,
        int betLevel,
        List<String> symbols,
        List<ExtraCell> extra,
        int spinStatus,
        int smallGameType,
        int removeStatus,
        int removeNum,
        BigDecimal winAmount,
        BigDecimal winAmountSum,
        List<WinMatch> winMatches) {

    public Step {
        if (deliveryIndex < 0) throw new IllegalArgumentException("deliveryIndex must be non-negative");
        betAmount = Objects.requireNonNull(betAmount, "betAmount").stripTrailingZeros();
        betSize = Objects.requireNonNull(betSize, "betSize").stripTrailingZeros();
        if (betLevel < 1) throw new IllegalArgumentException("betLevel must be positive");
        symbols = List.copyOf(Objects.requireNonNull(symbols, "symbols"));
        if (symbols.size() != GameRuleCore.CELLS) throw new IllegalArgumentException("board must contain 25 symbols");
        extra = List.copyOf(Objects.requireNonNull(extra, "extra"));
        if (spinStatus != 0 && spinStatus != 1) throw new IllegalArgumentException("spinStatus must be 0 or 1");
        if (smallGameType != 0 && smallGameType != 1) throw new IllegalArgumentException("smallGameType must be 0 or 1");
        if (removeStatus < 0 || removeStatus > 4) throw new IllegalArgumentException("removeStatus must be 0..4");
        if (removeNum < 0 || removeNum > GameRuleCore.COLLECTOR_CAP) {
            throw new IllegalArgumentException("removeNum outside 0..70");
        }
        winAmount = Objects.requireNonNull(winAmount, "winAmount").stripTrailingZeros();
        winAmountSum = Objects.requireNonNull(winAmountSum, "winAmountSum").stripTrailingZeros();
        winMatches = List.copyOf(Objects.requireNonNull(winMatches, "winMatches"));
    }

    public static Step fact(int deliveryIndex, BigDecimal betAmount, BigDecimal betSize, int betLevel,
                            List<String> symbols, List<ExtraCell> extra, int spinStatus,
                            int smallGameType, int removeStatus) {
        return new Step(deliveryIndex, betAmount, betSize, betLevel, symbols, extra, spinStatus,
            smallGameType, removeStatus, 0, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
    }
}
