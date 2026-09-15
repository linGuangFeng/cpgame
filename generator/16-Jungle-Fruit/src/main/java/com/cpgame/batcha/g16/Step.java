package com.cpgame.batcha.g16;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** One protocol Delivery/Step. Balances, sessions, clocks and HTTP envelopes are deliberately absent. */
public record Step(
        int deliveryIndex,
        BigDecimal betAmount,
        BigDecimal betSize,
        int betLevel,
        List<String> symbols,
        int spinStatus,
        int freeSpinNum,
        int nowFreeSpinCount,
        int smallGameType,
        BigDecimal roundWinAmount,
        BigDecimal winAmount,
        List<WinMatch> winMatches,
        List<String> winXKeys) {

    public Step {
        if (deliveryIndex < 0) throw new IllegalArgumentException("deliveryIndex must be non-negative");
        betAmount = Objects.requireNonNull(betAmount, "betAmount").stripTrailingZeros();
        betSize = Objects.requireNonNull(betSize, "betSize").stripTrailingZeros();
        if (betLevel < 1) throw new IllegalArgumentException("betLevel must be positive");
        symbols = List.copyOf(Objects.requireNonNull(symbols, "symbols"));
        if (symbols.size() != GameRuleCore.CELLS) throw new IllegalArgumentException("Jungle Fruit board must contain 36 symbols");
        if (spinStatus != 0 && spinStatus != 1) throw new IllegalArgumentException("spinStatus must be 0 or 1");
        if (freeSpinNum < 0 || nowFreeSpinCount < 0) throw new IllegalArgumentException("free-spin counters must be non-negative");
        if (smallGameType < 0 || smallGameType > 2) throw new IllegalArgumentException("smallGameType must be 0, 1 or 2");
        roundWinAmount = Objects.requireNonNull(roundWinAmount, "roundWinAmount").stripTrailingZeros();
        winAmount = Objects.requireNonNull(winAmount, "winAmount").stripTrailingZeros();
        winMatches = List.copyOf(Objects.requireNonNull(winMatches, "winMatches"));
        winXKeys = List.copyOf(Objects.requireNonNull(winXKeys, "winXKeys"));
    }

    public static Step fact(int deliveryIndex, BigDecimal betAmount, BigDecimal betSize,
                            int betLevel, List<String> symbols, int spinStatus,
                            int freeSpinNum, int nowFreeSpinCount, int smallGameType) {
        return new Step(deliveryIndex, betAmount, betSize, betLevel, symbols, spinStatus,
            freeSpinNum, nowFreeSpinCount, smallGameType, BigDecimal.ZERO, BigDecimal.ZERO,
            List.of(), List.of());
    }
}
