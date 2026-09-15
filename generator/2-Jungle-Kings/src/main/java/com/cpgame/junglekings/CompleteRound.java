package com.cpgame.junglekings;

import java.math.BigDecimal;
import java.util.List;

/** One paid Jungle Kings round: one or two chessboards, one terminal delivery. */
public record CompleteRound(
        int rawGameId,
        RoundMode mode,
        BigDecimal betSize,
        int betLevel,
        BigDecimal betAmount,
        List<String> chessboards,
        List<List<String>> boards,
        List<String> winPaylineKeys,
        List<String> winSymbolKeys,
        BigDecimal winAmount,
        int multiplier
) {
    public CompleteRound {
        chessboards = List.copyOf(chessboards);
        boards = boards.stream().map(List::copyOf).toList();
        winPaylineKeys = List.copyOf(winPaylineKeys);
        winSymbolKeys = List.copyOf(winSymbolKeys);
        betSize = betSize.stripTrailingZeros();
        betAmount = betAmount.stripTrailingZeros();
        winAmount = winAmount.stripTrailingZeros();
    }

    public List<String> logicalReels(int boardIndex) {
        return GameRuleCore.logicalReels(boards.get(boardIndex));
    }
}
