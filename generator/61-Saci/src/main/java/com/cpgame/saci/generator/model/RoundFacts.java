package com.cpgame.saci.generator.model;

import java.math.BigDecimal;
import java.util.List;

public record RoundFacts(String roundKey, int betLevel, BigDecimal betSize, RoundCandidate candidate) {
    public RoundFacts {
        if (roundKey == null || roundKey.isBlank()) throw new IllegalArgumentException("roundKey 无效");
        if (candidate == null) throw new IllegalArgumentException("candidate 无效");
    }
}
