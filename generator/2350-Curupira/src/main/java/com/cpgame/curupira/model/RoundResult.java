package com.cpgame.curupira.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record RoundResult(long roundKey, int deliveryIndex, BigDecimal lineBet, int level,
                          BigDecimal totalBet, BigDecimal startBalance, BigDecimal change,
                          BigDecimal endBalance, int multiplierSum, BigDecimal totalWin,
                          EvaluatedBoard board, Instant createdAt, long userId,
                          String opaqueToken, boolean paidRound) {
    public boolean terminal() { return true; }
    public boolean winning() { return totalWin.signum() > 0; }
    public List<Integer> expandingWildColumns() { return board.expandingWildColumns(); }
}
