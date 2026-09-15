package com.cpgame.coinmastergo.api;

public record ApiEnvelope<T>(int code, String info, T data) {
    public static <T> ApiEnvelope<T> ok(T data) { return new ApiEnvelope<>(200, "ok", data); }
}
