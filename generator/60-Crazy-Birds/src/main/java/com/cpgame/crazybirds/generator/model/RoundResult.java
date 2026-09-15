package com.cpgame.crazybirds.generator.model;

import java.math.BigDecimal;
import java.util.List;

public record RoundResult(
        String roundKey,
        BigDecimal bs,
        int bl,
        BigDecimal startingBalance,
        BigDecimal betAmount,
        BigDecimal totalWin,
        RoundMode mode,
        List<SpinStep> steps
) {
    public List<List<String>> boards() {
        return steps.stream().map(SpinStep::rskl).toList();
    }

    public BigDecimal endingBalance() {
        return steps.get(steps.size() - 1).pb();
    }
}
