package com.cpgame.saci.generator.model;

import java.util.List;

public record RoundCandidate(RoundMode mode, List<StepFact> steps) {
    public RoundCandidate {
        if (mode == null || steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("完整局候选不能为空");
        }
        steps = List.copyOf(steps);
    }
}
