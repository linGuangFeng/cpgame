package com.cpgame.saci.generator.model;

import java.util.List;

public record SymbolWin(String symbol, int reels, int copyWays, int cellFactor, int payout, List<Integer> coords) {
    public SymbolWin {
        coords = List.copyOf(coords);
    }

    public int waysContribution() {
        return copyWays * cellFactor * payout;
    }
}
