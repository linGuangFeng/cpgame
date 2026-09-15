package com.hd.cpgame.riocarnival.server.web;

import com.hd.cpgame.riocarnival.server.state.SessionStore;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ApiExceptionHandler {
    @ExceptionHandler(SessionStore.SessionException.class)
    public Envelope session(SessionStore.SessionException e){return Envelope.error(e.code,e.getMessage());}
    @ExceptionHandler({IllegalArgumentException.class,NumberFormatException.class})
    public Envelope request(Exception e){return Envelope.error("C10001",e.getMessage());}
}
