package com.cpgame.luckycatii.server;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class SessionService {
    private final BigDecimal initialBalance;
    private final RedisRoundStore roundStore;
    private final AtomicLong playerIds = new AtomicLong(50_000_000L);
    private final AtomicLong transferSeq = new AtomicLong(System.currentTimeMillis() << 12);
    private final Map<String, PlayerSession> byToken = new ConcurrentHashMap<>();

    public SessionService(BigDecimal initialBalance, RedisRoundStore roundStore) {
        this.initialBalance = initialBalance;
        this.roundStore = roundStore;
    }

    public PlayerSession verify(String launchToken) {
        if (launchToken == null || launchToken.isBlank()) return null;
        return byToken.computeIfAbsent(launchToken, token ->
                new PlayerSession(playerIds.incrementAndGet(), token, initialBalance, roundStore, transferSeq));
    }

    public PlayerSession authenticate(String token) {
        return token == null ? null : byToken.get(token);
    }
}
