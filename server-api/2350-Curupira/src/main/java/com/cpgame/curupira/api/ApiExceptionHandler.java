package com.cpgame.curupira.api;

import com.cpgame.curupira.core.GameRuleCore;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public final class ApiExceptionHandler {
    @ExceptionHandler(UnsupportedBehaviorException.class)
    public Map<String, Object> unsupported(UnsupportedBehaviorException error) {
        return ResponseFactory.error(4007, error.getMessage());
    }

    @ExceptionHandler(InvalidSignatureException.class)
    public Map<String, Object> invalidSignature(InvalidSignatureException error) {
        return ResponseFactory.error(4014, error.getMessage());
    }

    @ExceptionHandler(GameRuleCore.InsufficientBalanceException.class)
    public Map<String, Object> insufficient(GameRuleCore.InsufficientBalanceException error) {
        return ResponseFactory.error(4020, error.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, NumberFormatException.class})
    public Map<String, Object> badRequest(RuntimeException error) {
        return ResponseFactory.error(4000, error.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public Map<String, Object> redis(IllegalStateException error) {
        return ResponseFactory.error(4006, error.getMessage());
    }
}
