package com.cpgame.saci.generator.model;

import java.math.BigDecimal;

public record ResultAnalysis(
        RoundMode mode,
        BigDecimal bet,
        BigDecimal totalWin,
        int multiplierHundredths,
        int stepCount
) {}
