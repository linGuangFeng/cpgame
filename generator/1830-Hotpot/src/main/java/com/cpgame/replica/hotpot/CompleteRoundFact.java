package com.cpgame.replica.hotpot;

import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotBoard;

import java.util.ArrayList;
import java.util.List;

/** Fact-only Redis member. Every entry is one paid round through its final free-step delivery. */
public record CompleteRoundFact(int v, List<List<BoardFact>> spins) {
    public static final int VERSION = 1;

    public CompleteRoundFact {
        spins = List.copyOf(spins);
        if (v != VERSION || spins.isEmpty()) throw new IllegalArgumentException("invalid complete round");
        for (List<BoardFact> spin : spins) {
            if (spin == null || spin.isEmpty()) throw new IllegalArgumentException("spin has no pages");
        }
    }

    public record BoardFact(List<Integer> prop) {
        public BoardFact {
            prop = List.copyOf(prop);
            if (prop.size() != HotpotBoard.SIZE) throw new IllegalArgumentException("invalid board size");
            for (int symbol : prop) {
                if (symbol < HotpotBoard.MIN_SYMBOL || symbol > HotpotBoard.MAX_SYMBOL) {
                    throw new IllegalArgumentException("symbol out of range: " + symbol);
                }
            }
        }

        public HotpotBoard toBoard() {
            int[] values = new int[prop.size()];
            for (int i = 0; i < prop.size(); i++) values[i] = prop.get(i);
            return new HotpotBoard(values);
        }
    }

    public static BoardFact fromBoard(HotpotBoard board) {
        int[] prop = board.getProp();
        List<Integer> values = new ArrayList<>(prop.length);
        for (int symbol : prop) values.add(symbol);
        return new BoardFact(values);
    }
}
