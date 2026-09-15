package com.cpgame.curupira.api;

public final class UnsupportedBehaviorException extends RuntimeException {
    public UnsupportedBehaviorException(int type, int gameType) {
        super("Unsupported unresolved Curupira behavior: type=" + type + ", game_type=" + gameType);
    }
}
