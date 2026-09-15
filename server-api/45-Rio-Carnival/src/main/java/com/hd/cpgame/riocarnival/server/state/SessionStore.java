package com.hd.cpgame.riocarnival.server.state;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class SessionStore {
    private final StateRepository repository;
    private final BigDecimal initialBalance;
    private final Map<String,GameSession> byLaunch = new LinkedHashMap<String,GameSession>();
    private final Map<String,GameSession> byToken = new LinkedHashMap<String,GameSession>();
    private final AtomicLong playerIds = new AtomicLong(45000000L);
    private final SecureRandom secureRandom = new SecureRandom();

    public SessionStore(StateRepository repository, @Value("${rio.initial-balance:10000.00}") BigDecimal initialBalance) {
        this.repository=repository; this.initialBalance=initialBalance;
        for (GameSession session : repository.load().sessions) {
            byLaunch.put(session.launchHash, session);
            for (String hash : session.tokenHashes) byToken.put(hash, session);
            playerIds.set(Math.max(playerIds.get(), session.playerId+1));
        }
    }

    public synchronized Verification verify(String launchToken) {
        String launchHash = Hashing.sha256(launchToken);
        GameSession session = byLaunch.get(launchHash);
        if (session == null) {
            session = new GameSession(); session.sessionKey="session-"+randomToken(); session.launchHash=launchHash;
            session.playerId=playerIds.getAndIncrement(); session.balance=initialBalance;
            byLaunch.put(launchHash,session);
        }
        String token=randomToken(); String tokenHash=Hashing.sha256(token);
        session.tokenHashes.add(tokenHash); byToken.put(tokenHash,session); save();
        return new Verification(token,session);
    }

    public synchronized GameSession require(String token) {
        GameSession session = token == null ? null : byToken.get(Hashing.sha256(token));
        if (session == null) throw new SessionException("C10001", "invalid session");
        return session;
    }
    public synchronized void save() { repository.save(uniqueSessions()); }
    private Collection<GameSession> uniqueSessions() { return new ArrayList<GameSession>(byLaunch.values()); }
    private String randomToken() { byte[] b=new byte[24]; secureRandom.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }

    public static final class Verification {
        public final String token; public final GameSession session;
        Verification(String token, GameSession session) { this.token=token; this.session=session; }
    }
    public static final class SessionException extends RuntimeException {
        public final String code; public SessionException(String code,String message){super(message);this.code=code;}
    }
}
