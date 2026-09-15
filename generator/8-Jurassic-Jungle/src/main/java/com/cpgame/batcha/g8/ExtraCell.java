package com.cpgame.batcha.g8;

import java.util.Objects;

/** Giant-dragon animation fact: wire coord still shows this old low symbol before the board's high/wild. */
public record ExtraCell(int coord, String oldSymbol) {
    public ExtraCell {
        if (coord < 0 || coord > 44) throw new IllegalArgumentException("extra coord outside 5x5: " + coord);
        Objects.requireNonNull(oldSymbol, "oldSymbol");
        if (!GameRuleCore.LOW_SYMBOLS.contains(oldSymbol)) {
            throw new IllegalArgumentException("giant extra must be a low-paying symbol, got " + oldSymbol);
        }
    }
}
