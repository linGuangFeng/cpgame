package com.cpgame.luckycatii.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record RoundResult(
        String roundKey,
        long createdAtEpochSecond,
        BigDecimal betSize,
        int betLevel,
        BigDecimal betAmount,
        int gameMode,
        int respinReelIndex,
        List<String> respinSymbols,
        int rpx,
        List<String> paidBoard,
        List<String> finalBoard,
        Map<Integer, String> winningLines,
        BigDecimal award,
        List<RoundStep> steps) {

    public List<String> rskl() {
        return finalBoard;
    }
}
