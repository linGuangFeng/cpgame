package com.cpgame.luckynightmarket;

import java.util.List;
import java.util.Objects;

/** Immutable, money-independent facts for one complete paid round. */
public record RoundFact(Mode mode, List<Step> steps) {
    public enum Mode { ORDINARY_LOSS, ORDINARY_WIN, LUCKY_WHEEL, LUCKY_FEATURE }

    public RoundFact {
        Objects.requireNonNull(mode, "mode");
        steps = List.copyOf(steps);
        int expected = mode == Mode.LUCKY_FEATURE ? 8 : 1;
        if (steps.size() != expected) throw new IllegalArgumentException("Mode requires " + expected + " steps");
    }

    public boolean feature() { return mode == Mode.LUCKY_FEATURE; }

    /** ps is column-major; row zero is the bottom. Zero on muls is a wheel ticket. */
    public record Step(List<Integer> ps, List<Integer> muls, int wheelMultiplier) {
        public Step {
            ps = List.copyOf(ps);
            muls = List.copyOf(muls);
            if (ps.size() != 9 || muls.size() != 3) throw new IllegalArgumentException("Expected 9 symbols and 3 multipliers");
            for (int symbol : ps) {
                if (symbol < 0 || symbol > 6) throw new IllegalArgumentException("Symbol out of range");
            }
            for (int multiplier : muls) {
                if (multiplier != 0 && multiplier != 1 && multiplier != 2 && multiplier != 3
                        && multiplier != 5 && multiplier != 10 && multiplier != 15) {
                    throw new IllegalArgumentException("Invalid reel multiplier");
                }
            }
            // Only these two prizes have original paid-round evidence.
            if (wheelMultiplier != 0 && wheelMultiplier != 100 && wheelMultiplier != 200) {
                throw new IllegalArgumentException("Wheel prize lacks captured evidence");
            }
        }

        public boolean wheel() { return wheelMultiplier != 0; }
    }
}
