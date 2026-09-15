package com.cpgame.clubgoddess.api.controller;

import com.cpgame.clubgoddess.api.service.GameService;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrorHandler {
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public Map<String,Object> handle(RuntimeException e) {
        return GameService.map("code",40001,"data",null,"msg",e.getMessage(),"time",Long.toString(Instant.now().getEpochSecond()));
    }
}
