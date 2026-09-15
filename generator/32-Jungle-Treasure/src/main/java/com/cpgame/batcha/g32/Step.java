package com.cpgame.batcha.g32;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record Step(
        int deliveryIndex,
        BigDecimal betAmount,
        BigDecimal betSize,
        int betLevel,
        List<String> tokens,
        List<Integer> silver,
        List<Integer> gold,
        int spinStatus,
        int freeSpinNum,
        int nowFreeSpinCount,
        int smallGameType,
        int gameType,
        int roundPayX,
        BigDecimal roundWinAmount,
        BigDecimal winAmount,
        BigDecimal freeRoundWinAmount,
        List<WinMatch> winMatches) {

    public Step {
        if (deliveryIndex < 0) throw new IllegalArgumentException("deliveryIndex must be non-negative");
        betAmount = Objects.requireNonNull(betAmount).stripTrailingZeros();
        betSize = Objects.requireNonNull(betSize).stripTrailingZeros();
        tokens = List.copyOf(Objects.requireNonNull(tokens));
        silver = List.copyOf(Objects.requireNonNull(silver));
        gold = List.copyOf(Objects.requireNonNull(gold));
        if (spinStatus != 0 && spinStatus != 1) throw new IllegalArgumentException("spinStatus must be 0 or 1");
        roundWinAmount = Objects.requireNonNull(roundWinAmount).stripTrailingZeros();
        winAmount = Objects.requireNonNull(winAmount).stripTrailingZeros();
        freeRoundWinAmount = Objects.requireNonNull(freeRoundWinAmount).stripTrailingZeros();
        winMatches = List.copyOf(Objects.requireNonNull(winMatches));
    }

    public static Step fact(int deliveryIndex, BigDecimal betAmount, BigDecimal betSize, int betLevel,
                            List<String> tokens, List<Integer> silver, List<Integer> gold) {
        return new Step(deliveryIndex, betAmount, betSize, betLevel, tokens, silver, gold,
            1, 0, 0, 0, 1, 1, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
    }

    public List<String> winSymbolKeys() {
        return winMatches.stream().map(WinMatch::symbolKey).toList();
    }
}
