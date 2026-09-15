package com.cpgame.luckywheel.core;

import java.math.BigDecimal;

/** ResultUtil 从最小事实独立反推的派生结果。 */
public record ResultAnalysis(
        OutcomeType outcome,
        BigDecimal baseAward,
        BigDecimal featureAward,
        BigDecimal totalAward,
        boolean continuationRequired
) { }
