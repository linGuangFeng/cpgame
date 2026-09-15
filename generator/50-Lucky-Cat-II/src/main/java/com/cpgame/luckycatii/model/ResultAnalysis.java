package com.cpgame.luckycatii.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ResultAnalysis(
        RoundMode redisPoolMode,
        boolean luckyRespin,
        boolean wheel,
        int gameMode,
        int respinReelIndex,
        int rpx,
        int integerMultiplier,
        Map<Integer, String> winningLines,
        BigDecimal betAmount,
        BigDecimal award,
        List<String> resultPools,
        boolean terminal) {}
