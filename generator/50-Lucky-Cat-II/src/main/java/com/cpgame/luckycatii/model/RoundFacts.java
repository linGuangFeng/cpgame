package com.cpgame.luckycatii.model;

import java.math.BigDecimal;
import java.util.List;

/** Irreducible complete-round facts stored in a Redis member. */
public record RoundFacts(
        String roundKey,
        long createdAtEpochSecond,
        BigDecimal betSize,
        int betLevel,
        List<String> paidBoard,
        List<String> finalBoard,
        int rpx,
        boolean luckyRespin) {}
