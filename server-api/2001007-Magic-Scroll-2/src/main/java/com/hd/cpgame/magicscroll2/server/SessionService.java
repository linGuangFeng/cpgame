package com.hd.cpgame.magicscroll2.server;

import com.hd.cpgame.magicscroll2.core.GameConstants;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class SessionService {
    private final BigDecimal initialBalance;
    private final RedisRoundStore roundStore;
    private final SecureRandom random = new SecureRandom();
    private final AtomicLong playerIds = new AtomicLong(2_001_007_000_000L);
    private final Map<String, PlayerSession> byOtk = new ConcurrentHashMap<>();
    private final Map<String, PlayerSession> byAtk = new ConcurrentHashMap<>();

    public SessionService(BigDecimal initialBalance, RedisRoundStore roundStore) {
        this.initialBalance = initialBalance;
        this.roundStore = roundStore;
    }

    public PlayerSession verify(String gameId, String otk, String language) {
        if (!GameConstants.GAME_ID.equals(gameId)) throw new ApiException("INVALID_GAME", "gi must be 2001007");
        if (otk == null || otk.isBlank()) throw new ApiException("INVALID_OTK", "otk is required");
        if (!"en".equals(language) && !"pt".equals(language)) {
            throw new ApiException("UNSUPPORTED_LANGUAGE", "supported languages are en and pt");
        }
        return byOtk.computeIfAbsent(otk, token -> {
            byte[] bytes = new byte[24];
            random.nextBytes(bytes);
            String atk = "local-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            long userId = playerIds.incrementAndGet();
            PlayerSession session = new PlayerSession(userId, token, atk, language, initialBalance, roundStore);
            byAtk.put(atk, session);
            return session;
        });
    }

    public PlayerSession require(String atk) {
        if (atk == null || atk.isBlank()) throw new ApiException("SESSION_REQUIRED", "atk is required");
        PlayerSession session = byAtk.get(atk);
        if (session == null) throw new ApiException("SESSION_INVALID", "atk is unknown");
        return session;
    }
}
