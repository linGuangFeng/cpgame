package com.cpgame.crazypiggy.generator.model;

import java.util.List;

/** 随机候选只承载不可重算事实，不承载派奖推导值。 */
public record RoundCandidate(
        List<String> symbols,
        List<Integer> wheelPositions,
        List<Integer> wheelMultipliers) {

    public RoundCandidate {
        symbols = List.copyOf(symbols);
        wheelPositions = List.copyOf(wheelPositions);
        wheelMultipliers = List.copyOf(wheelMultipliers);
    }
}
