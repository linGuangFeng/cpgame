package com.cpgame.saci.generator.model;

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
        if (steps.isEmpty() || !steps.get(steps.size() - 1).roundTerminal()) {
            throw new IllegalArgumentException("完整 Round 必须以 ss=1 且 fsn=nfsc 且 rsn=nrsc 结束");
        }
        for (int i = 0; i < steps.size() - 1; i++) {
            if (steps.get(i).roundTerminal()) {
                throw new IllegalArgumentException("完整 Round 的非末尾 Step 不得整局终局");
            }
        }
    }

    public BigDecimal betAmount() {
        return steps.get(0).ba();
    }

    public BigDecimal totalWin() {
        return endingBalance().subtract(startingBalance).add(betAmount());
    }

    public BigDecimal endingBalance() {
        return steps.get(steps.size() - 1).pb();
    }

    public RoundCandidate candidate() {
        return new RoundCandidate(mode, steps.stream().map(SpinStep::fact).toList());
    }
}
