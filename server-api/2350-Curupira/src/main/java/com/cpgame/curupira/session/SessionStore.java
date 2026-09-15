package com.cpgame.curupira.session;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public final class SessionStore {
    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final BigDecimal initialBalance;
    private final int historyLimit;
    private final SecureRandom random = new SecureRandom();

    public SessionStore(@Value("${curupira.initial-balance}") BigDecimal initialBalance,
                        @Value("${curupira.history-limit}") int historyLimit) {
        this.initialBalance = initialBalance;
        this.historyLimit = historyLimit;
    }

    public SessionState require(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token is required");
        }
        return sessions.computeIfAbsent(token, this::newSession);
    }

    private SessionState newSession(String token) {
        byte[] opaqueBytes = new byte[16];
        random.nextBytes(opaqueBytes);
        return new SessionState(token, stableUserId(token), HexFormat.of().formatHex(opaqueBytes),
                initialBalance, historyLimit);
    }

    private static long stableUserId(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int i = 0; i < 7; i++) {
                value = (value << 8) | Byte.toUnsignedLong(hash[i]);
            }
            return 10_000_000L + value % 90_000_000L;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
