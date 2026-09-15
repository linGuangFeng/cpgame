package com.cpgame.batchc.cybergo.server;

final class ApiException extends RuntimeException {
    final int httpStatus;
    final int code;
    ApiException(int httpStatus, int code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }
}
