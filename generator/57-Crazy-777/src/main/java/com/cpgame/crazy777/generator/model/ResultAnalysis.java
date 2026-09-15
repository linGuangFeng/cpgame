package com.cpgame.crazy777.generator.model;

import java.math.BigDecimal;

public record ResultAnalysis(
        RoundMode mode,
        BigDecimal betAmount,
        BigDecimal totalWin,
        BigDecimal totalMultiplier,
        int paidStarts,
        int freeDeliveries,
        int stepCount
) {}
