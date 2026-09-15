package com.cpgame.batcha.g32;

public record Token(String symbol, int height, int reel, int index, int coord, boolean extra) {
    public String wire() {
        return height + symbol;
    }
}
