package com.cpgame.sharpshooter.core;

import java.util.*;

/** One paid start plus every free continuation belonging to it. */
public record CompleteRound(List<Spin> spins) {
    public CompleteRound { spins = List.copyOf(spins); if (spins.isEmpty()) throw new IllegalArgumentException("empty round"); }
    public record Spin(boolean paid, int freeTotal, int freeRemaining, int newFree, List<Cascade> cascades) {
        public Spin { cascades = List.copyOf(cascades); if (cascades.isEmpty()) throw new IllegalArgumentException("empty spin"); }
    }
    public record Cascade(int[] symbols, boolean[] gold) {
        public Cascade { symbols=symbols.clone(); gold=gold.clone(); }
        @Override public int[] symbols(){return symbols.clone();}
        @Override public boolean[] gold(){return gold.clone();}
    }
}
