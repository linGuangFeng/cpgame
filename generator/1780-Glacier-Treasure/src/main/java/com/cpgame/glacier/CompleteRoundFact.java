package com.cpgame.glacier;

import java.util.List;

/** One paid round through its last free delivery. Redis stores the codec of this fact. */
public record CompleteRoundFact(boolean featureBuy, List<List<GameRuleCore.Board>> spins) {
    public CompleteRoundFact {
        spins = spins.stream().map(List::copyOf).toList();
        if (spins.isEmpty()) throw new IllegalArgumentException("empty complete round");
        for (var spin : spins) if (spin.isEmpty()) throw new IllegalArgumentException("empty spin");
    }
    public boolean special() { return spins.size() > 1; }
}
