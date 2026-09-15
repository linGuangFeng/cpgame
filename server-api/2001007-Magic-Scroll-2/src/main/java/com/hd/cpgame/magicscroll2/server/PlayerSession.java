package com.hd.cpgame.magicscroll2.server;

import com.hd.cpgame.magicscroll2.core.DeliveryRuntimeUtil;
import com.hd.cpgame.magicscroll2.core.GameConstants;
import com.hd.cpgame.magicscroll2.core.GeneratedRound;
import com.hd.cpgame.magicscroll2.core.RoundStep;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PlayerSession {
    private final long userId;
    private final String otk;
    private final String atk;
    private final String language;
    private final RedisRoundStore roundStore;
    private final DeliveryRuntimeUtil delivery = new DeliveryRuntimeUtil();
    private BigDecimal balance;
    private GeneratedRound pendingRound;
    private int deliveryIndex = -1;
    private boolean payoutCredited;
    private final Map<String, Map<String, Object>> idempotent = new LinkedHashMap<>();
    private final List<HistoryRecord> history = new ArrayList<>();

    PlayerSession(long userId, String otk, String atk, String language, BigDecimal balance,
                  RedisRoundStore roundStore) {
        this.userId = userId;
        this.otk = otk;
        this.atk = atk;
        this.language = language;
        this.balance = money(balance);
        this.roundStore = roundStore;
    }

    public synchronized Map<String, Object> spin(BigDecimal bet, BigDecimal mult, int gameType,
                                                 boolean reconnect, String idempotencyKey) {
        if (gameType != 1) throw new ApiException("CAPABILITY_GATED", "gameType " + gameType + " is not implemented");
        String fingerprint = bet.toPlainString() + "|" + mult.toPlainString() + "|" + gameType + "|" + reconnect;
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Map<String, Object> cached = idempotent.get(idempotencyKey);
            if (cached != null) return cached;
        }
        Map<String, Object> result;
        if (reconnect || bet.compareTo(BigDecimal.ZERO) < 0) {
            result = replay();
        } else if (bet.signum() == 0 && mult.signum() == 0) {
            result = deliverNext();
        } else {
            if (bet.compareTo(GameConstants.MINIMUM_TOTAL_BET) < 0 || mult.signum() <= 0) {
                throw new ApiException("INVALID_WAGER", "paid bet must be >=0.40 and mult must be >0");
            }
            if (pendingRound != null) {
                DeliveryRuntimeUtil.Projection current = currentProjection();
                if (current != null && current.isTerminal() && payoutCredited) {
                    pendingRound = null;
                    deliveryIndex = -1;
                    payoutCredited = false;
                } else {
                    throw new ApiException("ROUND_ACTIVE", "finish or reconnect the active Round first");
                }
            }
            if (balance.compareTo(bet) < 0) throw new ApiException("INSUFFICIENT_BALANCE", "balance is below the legal wager");
            balance = money(balance.subtract(bet));
            pendingRound = roundStore.claim(bet);
            deliveryIndex = -1;
            payoutCredited = false;
            result = deliverNext();
        }
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotent.put(idempotencyKey, result);
            while (idempotent.size() > 1000) idempotent.remove(idempotent.keySet().iterator().next());
        }
        return result;
    }

    private Map<String, Object> deliverNext() {
        if (pendingRound == null) throw new ApiException("NO_ACTIVE_ROUND", "no Round is available for continuation");
        int next = deliveryIndex + 1;
        if (next >= pendingRound.getSteps().size()) {
            throw new ApiException("ROUND_TERMINAL", "terminal Delivery was already delivered");
        }
        deliveryIndex = next;
        return project();
    }

    private Map<String, Object> replay() {
        if (pendingRound == null || deliveryIndex < 0) {
            throw new ApiException("NO_ACTIVE_ROUND", "no Delivery is available to replay");
        }
        return project();
    }

    private DeliveryRuntimeUtil.Projection currentProjection() {
        if (pendingRound == null || deliveryIndex < 0) return null;
        return delivery.project(pendingRound, deliveryIndex);
    }

    private Map<String, Object> project() {
        DeliveryRuntimeUtil.Projection projection = delivery.project(pendingRound, deliveryIndex);
        if (projection.isTerminal() && !payoutCredited) {
            balance = money(balance.add(pendingRound.getPayout()));
            appendHistory();
            payoutCredited = true;
        }
        BigDecimal wireBalance = projection.isTerminal() ? balance : BigDecimal.ZERO.setScale(2);
        Map<String, Object> dt = new LinkedHashMap<>();
        dt.put("bl", wireBalance);
        dt.put("formation", projection.getFormation());
        dt.put("free", false);
        return dt;
    }

    private void appendHistory() {
        List<String> formations = new ArrayList<>();
        for (RoundStep step : pendingRound.getSteps()) formations.add(step.getFormation());
        history.add(new HistoryRecord(pendingRound.getRoundKey(), Instant.now().getEpochSecond(),
                money(pendingRound.getPaidBet()),
                money(pendingRound.getPayout().subtract(pendingRound.getPaidBet())),
                balance, BigDecimal.ONE, formations, pendingRound.getMode().name(),
                pendingRound.getSteps().size()));
    }

    public synchronized List<HistoryRecord> history() { return new ArrayList<>(history); }
    public long userId() { return userId; }
    public String atk() { return atk; }
    public String otk() { return otk; }
    public String language() { return language; }
    public synchronized BigDecimal balance() { return balance; }
    public String playerName() {
        String id = Long.toString(userId);
        return "Demo_" + id.substring(Math.max(0, id.length() - 6));
    }

    private static BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP); }

    public static final class HistoryRecord {
        public final String roundKey;
        public final long createdAt;
        public final BigDecimal bet;
        public final BigDecimal transfer;
        public final BigDecimal balance;
        public final BigDecimal mult;
        public final List<String> formations;
        public final String mode;
        public final int deliveryCount;
        HistoryRecord(String roundKey, long createdAt, BigDecimal bet, BigDecimal transfer,
                      BigDecimal balance, BigDecimal mult, List<String> formations, String mode,
                      int deliveryCount) {
            this.roundKey = roundKey;
            this.createdAt = createdAt;
            this.bet = bet;
            this.transfer = transfer;
            this.balance = balance;
            this.mult = mult;
            this.formations = formations;
            this.mode = mode;
            this.deliveryCount = deliveryCount;
        }
    }
}
