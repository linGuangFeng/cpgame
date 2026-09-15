package com.cpgame.coinmastergo.api;

import com.cpgame.coinmastergo.service.GameException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(GameException.class)
    public ApiEnvelope<Void> gameError(GameException error) {
        return new ApiEnvelope<>(error.getCode(), error.getMessage(), null);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, IllegalArgumentException.class})
    public ApiEnvelope<Void> badRequest(Exception error) {
        return new ApiEnvelope<>(400, error.getMessage(), null);
    }
}
