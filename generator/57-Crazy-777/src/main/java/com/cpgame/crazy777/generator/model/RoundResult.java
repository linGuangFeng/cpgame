package com.cpgame.crazy777.generator.model;

import java.math.BigDecimal;
import java.util.List;

public record RoundResult(
        String roundKey,
        RoundMode mode,
        int bl,
        BigDecimal bs,
        BigDecimal startingBalance,
        List<SpinStep> steps
) {
    public RoundResult {
        steps = List.copyOf(steps);
        if (steps.isEmpty() || !steps.get(steps.size() - 1).terminal()) {
            throw new IllegalArgumentException("完整 Round 必须以合法终局 Step 结束");
        }
        for (int i = 0; i < steps.size() - 1; i++) {
            if (steps.get(i).terminal()) {
                throw new IllegalArgumentException("完整 Round 的非末尾 Step 不得终局");
            }
        }
    }

    public BigDecimal betAmount() {
        return steps.get(0).ba();
    }

    public BigDecimal totalWin() {
        return steps.stream().map(SpinStep::wa).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal endingBalance() {
        return steps.get(steps.size() - 1).pb();
    }

    public List<List<String>> boards() {
        return steps.stream().map(SpinStep::rskl).toList();
    }
}
