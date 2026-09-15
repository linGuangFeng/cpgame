package com.cpgame.batchc.cybergo.server;

import com.cpgame.batchc.cybergo.CyberGoModels.CompleteRound;
import com.cpgame.batchc.cybergo.CyberGoModels.Step;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** 内存会话仓库：断线后凭同一不透明token恢复活动Round、roundKey和deliveryIndex。 */
final class SessionStore {
    private final ConcurrentHashMap<String, PlayerSession> aliases = new ConcurrentHashMap<>();
    private final AtomicLong playerIds = new AtomicLong(52_000_000L);
    private final SecureRandom random = new SecureRandom();
    private final BigDecimal initialBalance;

    SessionStore(BigDecimal initialBalance) { this.initialBalance = initialBalance.setScale(2); }

    PlayerSession verify(String bootstrapToken) {
        String key = bootstrapToken == null || bootstrapToken.isBlank() ? "cyber-go-local-session" : bootstrapToken;
        PlayerSession existing = aliases.get(key);
        if (existing != null) return existing;
        synchronized (this) {
            existing = aliases.get(key);
            if (existing != null) return existing;
            String token = "cg52_" + Long.toUnsignedString(random.nextLong(), 36) + Long.toUnsignedString(random.nextLong(), 36);
            PlayerSession created = new PlayerSession(playerIds.incrementAndGet(), token, initialBalance);
            aliases.put(key, created);
            aliases.put(token, created);
            return created;
        }
    }

    PlayerSession require(String token) {
        if (token == null || token.isBlank() || !aliases.containsKey(token)) throw new ApiException(401, 401, "无效或缺失会话token");
        return aliases.get(token);
    }

    static final class PlayerSession {
        final long playerId;
        final String token;
        BigDecimal balance;
        ActiveRound activeRound;
        final Deque<HistoryRound> history = new ArrayDeque<>();
        final LinkedHashMap<String, Object> idempotency = new LinkedHashMap<>() {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) { return size() > 512; }
        };

        PlayerSession(long playerId, String token, BigDecimal balance) {
            this.playerId = playerId;
            this.token = token;
            this.balance = balance;
        }

        synchronized Object idempotent(String route, String key, Supplier<Object> action) {
            if (key == null || key.isBlank()) return action.get();
            String scoped = route + "\n" + key;
            if (idempotency.containsKey(scoped)) return idempotency.get(scoped);
            Object response = action.get();
            idempotency.put(scoped, response);
            return response;
        }

        synchronized List<HistoryRound> historySnapshot() { return List.copyOf(new ArrayList<>(history)); }
    }

    static final class ActiveRound {
        final CompleteRound round;
        int deliveryIndex;

        ActiveRound(CompleteRound round) { this.round = round; }
        Step lastDelivered() { return deliveryIndex == 0 ? null : round.deliveries().get(deliveryIndex - 1); }
        boolean hasNext() { return deliveryIndex < round.deliveries().size(); }
        Step next() { return round.deliveries().get(deliveryIndex++); }
    }

    record HistoryRound(CompleteRound round, BigDecimal balanceAfter) { }
}
