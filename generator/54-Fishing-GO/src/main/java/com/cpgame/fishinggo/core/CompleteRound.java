package com.cpgame.fishinggo.core;

import java.math.BigDecimal;
import java.util.List;

public record CompleteRound(List<Step> steps) {
    public CompleteRound {
        steps = List.copyOf(steps);
        if (steps.isEmpty()) throw new IllegalArgumentException("empty round");
    }

    public record Step(List<String> board, int rpx, int apx, int fsn, int nfsc, int gt, int smallGameType, int ss,
                       BigDecimal ba, BigDecimal wa, BigDecimal rwa) {
        public Step {
            board = List.copyOf(board);
        }
        public boolean terminal() { return fsn == 0 || fsn == nfsc; }
    }

    public boolean special() { return steps.size() > 1; }
    public boolean win() { return steps.get(steps.size() - 1).rwa().signum() > 0; }
}
