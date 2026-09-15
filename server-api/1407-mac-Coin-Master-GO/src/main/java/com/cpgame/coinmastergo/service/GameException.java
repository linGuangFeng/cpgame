package com.cpgame.coinmastergo.service;

public class GameException extends RuntimeException {
    private final int code;

    public GameException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() { return code; }
}
