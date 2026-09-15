package com.cpgame.crazy777.server;

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
    private final AtomicLong playerIds = new AtomicLong(57_000_000L);
    private final Map<String, PlayerSession> byLaunchToken = new ConcurrentHashMap<>();
    private final Map<String, PlayerSession> byRuntimeToken = new ConcurrentHashMap<>();

    public SessionService(BigDecimal initialBalance, RedisRoundStore roundStore) {
        this.initialBalance = initialBalance;
        this.roundStore = roundStore;
    }

    public PlayerSession verify(String launchToken) {
        if (launchToken == null || launchToken.isBlank()) return null;
        return byLaunchToken.computeIfAbsent(launchToken, token -> {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String runtime = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            PlayerSession session = new PlayerSession(playerIds.incrementAndGet(), token, runtime,
                    initialBalance, roundStore);
            byRuntimeToken.put(runtime, session);
            return session;
        });
    }

    public PlayerSession authenticate(String runtimeToken) {
        return runtimeToken == null ? null : byRuntimeToken.get(runtimeToken);
    }
}
