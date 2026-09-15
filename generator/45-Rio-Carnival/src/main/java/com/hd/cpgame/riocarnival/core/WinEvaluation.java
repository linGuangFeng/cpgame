package com.hd.cpgame.riocarnival.core;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public final class WinEvaluation {
    public final BigDecimal award;
    public final Map<String, Map<String, Integer>> matches;
    public final Map<Integer, BigDecimal> lineAwards;

    WinEvaluation(BigDecimal award, Map<String, Map<String, Integer>> matches, Map<Integer, BigDecimal> lineAwards) {
        this.award = award;
        this.matches = new LinkedHashMap<String, Map<String, Integer>>(matches);
        this.lineAwards = new LinkedHashMap<Integer, BigDecimal>(lineAwards);
    }
}
