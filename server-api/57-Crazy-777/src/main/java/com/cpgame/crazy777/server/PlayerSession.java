package com.cpgame.crazy777.server;

import com.cpgame.crazy777.generator.GameRules;
import com.cpgame.crazy777.generator.ResultUtil;
import com.cpgame.crazy777.generator.model.RoundResult;
import com.cpgame.crazy777.generator.model.SpinStep;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PlayerSession {
    private final long playerId;
    private final String launchToken;
    private final String runtimeToken;
    private final RedisRoundStore roundStore;
    private BigDecimal balance;
    private Map<String, Object> last;
    private RoundResult pendingRound;
    private int nextDeliveryIndex;
    private final Map<String, Cached> idempotentResponses = new LinkedHashMap<>();
    private final List<HistoryRecord> history = new ArrayList<>();

    PlayerSession(long playerId, String launchToken, String runtimeToken, BigDecimal balance,
                  RedisRoundStore roundStore) {
        this.playerId = playerId;
        this.launchToken = launchToken;
        this.runtimeToken = runtimeToken;
        this.balance = balance;
        this.roundStore = roundStore;
        this.last = idleLast(balance);
    }

    private static Map<String, Object> idleLast(BigDecimal balance) {
        SpinStep idle = new SpinStep(BigDecimal.ZERO, 1, new BigDecimal("0.5"), 0, 1, 0, balance, 1,
                GameRules.IDLE_BOARD, BigDecimal.ZERO, 0, 1, BigDecimal.ZERO, Map.of());
        return Collections.unmodifiableMap(idle.protocolMap());
    }

    public synchronized Map<String, Object> spin(BigDecimal bs, int bl, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Cached cached = idempotentResponses.get(idempotencyKey);
            if (cached != null) return cached.data();
        }
        if (pendingRound == null) {
            BigDecimal bet = GameRules.betAmount(bl, bs);
            if (balance.compareTo(bet) < 0) throw new IllegalStateException("INSUFFICIENT_BALANCE");
            RoundResult claimed = roundStore.claim(bs, bl, balance);
            ResultUtil.analyze(claimed);
            pendingRound = claimed;
            nextDeliveryIndex = 0;
        } else if (pendingRound.bl() != bl || pendingRound.bs().compareTo(bs) != 0) {
            throw new IllegalArgumentException("活动 Round 必须沿用触发时的 bl/bs");
        }
        SpinStep step = pendingRound.steps().get(nextDeliveryIndex);
        int deliveryIndex = nextDeliveryIndex;
        balance = step.pb();
        last = Collections.unmodifiableMap(new LinkedHashMap<>(step.protocolMap()));
        nextDeliveryIndex++;
        boolean terminal = step.terminal();
        if (terminal) {
            history.add(new HistoryRecord(pendingRound, Instant.now().getEpochSecond()));
            pendingRound = null;
            nextDeliveryIndex = 0;
        }
        Map<String, Object> data = new LinkedHashMap<>(step.protocolMap());
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotentResponses.put(idempotencyKey, new Cached(data, deliveryIndex));
            while (idempotentResponses.size() > 1000) {
                idempotentResponses.remove(idempotentResponses.keySet().iterator().next());
            }
        }
        return data;
    }

    public synchronized Map<String, Object> historyList(int pageIndex) {
        if (pageIndex < 1) throw new IllegalArgumentException("page_index 必须从 1 开始");
        List<HistoryRecord> filtered = new ArrayList<>(history);
        Collections.reverse(filtered);
        int from = Math.min((pageIndex - 1) * 10, filtered.size());
        int to = Math.min(from + 10, filtered.size());
        List<Map<String, Object>> entries = new ArrayList<>();
        BigDecimal totalBet = BigDecimal.ZERO;
        BigDecimal totalWin = BigDecimal.ZERO;
        for (HistoryRecord record : filtered) {
            totalBet = totalBet.add(record.round.betAmount());
            totalWin = totalWin.add(record.round.totalWin());
        }
        for (HistoryRecord record : filtered.subList(from, to)) entries.add(record.listEntry());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ba", SpinStep.moneyText(totalBet));
        result.put("end", to >= filtered.size() ? 1 : 0);
        result.put("lc", filtered.size());
        result.put("ll", entries);
        result.put("wa", SpinStep.moneyText(totalWin));
        return result;
    }

    public synchronized Map<String, Object> historyDetail(String transferId) {
        return history.stream().filter(item -> item.transferId.equals(transferId)).findFirst()
                .map(record -> record.detail(balance))
                .orElse(null);
    }

    public long playerId() { return playerId; }
    public String launchToken() { return launchToken; }
    public String runtimeToken() { return runtimeToken; }
    public synchronized BigDecimal balance() { return balance; }
    public synchronized Map<String, Object> last() { return last; }
    public synchronized String lastRoundKey() {
        return pendingRound != null ? pendingRound.roundKey()
                : history.isEmpty() ? null : history.get(history.size() - 1).round.roundKey();
    }
    public synchronized int deliveryIndex() { return nextDeliveryIndex; }
    public synchronized int historyCount() { return history.size(); }

    private record Cached(Map<String, Object> data, int deliveryIndex) {}

    private static final class HistoryRecord {
        private final RoundResult round;
        private final long createdAt;
        private final String transferId;
        private final String bid;

        private HistoryRecord(RoundResult round, long createdAt) {
            this.round = round;
            this.createdAt = createdAt;
            this.transferId = round.roundKey();
            this.bid = "57-" + round.roundKey();
        }

        Map<String, Object> listEntry() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ba", SpinStep.moneyText(round.betAmount()));
            result.put("baf", SpinStep.moneyText(round.endingBalance()));
            result.put("bid", bid);
            result.put("ca", createdAt);
            result.put("fe", 0);
            result.put("gm", null);
            result.put("gt", GameRules.GAME_ID);
            result.put("tis", transferId);
            result.put("wa", SpinStep.moneyText(round.totalWin()));
            return result;
        }

        Map<String, Object> detail(BigDecimal sessionBalance) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("baf", sessionBalance);
            result.put("bid", bid);
            result.put("bsl", List.of(detailStep(round.steps().get(0))));
            if (round.steps().size() > 1) {
                result.put("fsl", round.steps().subList(1, round.steps().size()).stream().map(this::detailStep).toList());
            }
            return result;
        }

        private Map<String, Object> detailStep(SpinStep step) {
            Map<String, Object> result = new LinkedHashMap<>(step.protocolMap());
            result.put("balance_after", SpinStep.moneyText(step.pb()));
            result.put("bet_level", step.bl());
            result.put("bet_size", step.bs());
            result.put("bid", bid);
            result.put("ca", createdAt);
            result.put("created_at", createdAt);
            result.put("cc", "BRL");
            result.put("cs", "R$");
            return result;
        }
    }
}
