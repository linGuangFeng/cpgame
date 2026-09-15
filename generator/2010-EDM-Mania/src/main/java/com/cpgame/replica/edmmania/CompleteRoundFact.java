package com.cpgame.replica.edmmania;

import java.util.List;

/** Fact-only Redis member. Every entry is one paid round through its final delivery. */
public record CompleteRoundFact(int v, boolean featureBuy, List<List<BoardFact>> spins) {
    public static final int VERSION = 1;

    public CompleteRoundFact {
        spins = List.copyOf(spins);
        if (v != VERSION || spins.isEmpty()) throw new IllegalArgumentException("invalid complete round");
    }

    public record BoardFact(List<Integer> prop, List<Integer> trl, List<List<Integer>> grids,
                            List<List<Integer>> gf, List<List<Integer>> sl) {
        public BoardFact(List<Integer> prop, List<Integer> trl) {
            this(prop, trl, List.of(), List.of(), List.of());
        }
        public BoardFact {
            prop = List.copyOf(prop);
            trl = List.copyOf(trl);
            grids = immutableGroups(grids);
            gf = immutableGroups(gf);
            sl = immutableGroups(sl);
            if (prop.size() != 30 || trl.size() != 4) throw new IllegalArgumentException("invalid board size");
        }

        private static List<List<Integer>> immutableGroups(List<List<Integer>> groups) {
            if (groups == null) return List.of();
            return groups.stream().map(List::copyOf).toList();
        }
    }
}
