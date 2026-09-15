package com.cpgame.fiesta;

import java.util.List;

public final class GameRound {
    private final List<RoundState> states;
    public GameRound(List<RoundState> states){ if(states == null || states.isEmpty()) throw new IllegalArgumentException("empty round"); this.states=List.copyOf(states); }
    public List<RoundState> states(){ return states; }
}

