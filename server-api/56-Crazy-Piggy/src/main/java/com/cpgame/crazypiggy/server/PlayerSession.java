package com.cpgame.crazypiggy.server;

import com.cpgame.crazypiggy.generator.GameRules;
import com.cpgame.crazypiggy.generator.ResultUtil;
import com.cpgame.crazypiggy.generator.model.RoundResult;

import java.math.BigDecimal;
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
    private RoundLedger lastLedger;
    private final Map<String, Map<String, Object>> idempotentResponses = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> historyDetails = new LinkedHashMap<>();

    PlayerSession(long playerId, String launchToken, String runtimeToken, BigDecimal balance,
                  RedisRoundStore roundStore) {
        this.playerId = playerId;
        this.launchToken = launchToken;
        this.runtimeToken = runtimeToken;
        this.balance = balance;
        this.roundStore = roundStore;
        this.last = initialLast(balance);
    }

    private static Map<String, Object> initialLast(BigDecimal balance) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("ba", BigDecimal.ZERO); value.put("bl", 1); value.put("bs", new BigDecimal("0.5"));
        value.put("ca", System.currentTimeMillis() / 1000); value.put("fwa", BigDecimal.ZERO);
        value.put("fwtl", List.of()); value.put("fwxl", List.of()); value.put("gm", 0); value.put("gt", 1);
        value.put("pb", balance.setScale(2).toPlainString());
        value.put("rskl", List.of("H2","H3","H4","H5","H6","H7","H3","H4","H5"));
        value.put("small_game_type", 0); value.put("wa", BigDecimal.ZERO); value.put("wmkl", List.of());
        return Collections.unmodifiableMap(value);
    }

    public synchronized Map<String, Object> spin(BigDecimal bs, int bl, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Map<String, Object> cached = idempotentResponses.get(idempotencyKey);
            if (cached != null) return cached;
        }
        BigDecimal bet = bs.multiply(BigDecimal.valueOf(bl));
        if (balance.compareTo(bet) < 0) throw new IllegalStateException("INSUFFICIENT_BALANCE");
        RoundResult round = roundStore.claim(bs, bl);
        ResultUtil.verify(round);
        BigDecimal postBalance = balance.subtract(round.betAmount()).add(round.totalAward());
        RoundLedger ledger = new RoundLedger(round);
        Map<String, Object> response = SpinWireMapper.toSpinData(round, postBalance, ledger);
        balance = postBalance;
        lastLedger = ledger;
        last = SpinWireMapper.toLastData(round, postBalance, response);
        historyDetails.put(round.roundKey(), buildHistoryDetail(round, response));
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotentResponses.put(idempotencyKey, Collections.unmodifiableMap(new LinkedHashMap<>(response)));
        }
        return response;
    }

    private Map<String, Object> buildHistoryDetail(RoundResult round, Map<String, Object> spin) {
        Map<String, Object> detail = new LinkedHashMap<>(spin);
        detail.put("bs", round.betSize());
        detail.put("bl", round.betLevel());
        detail.put("ca", round.createdAtEpochSecond());
        detail.put("gt", 1);
        return Collections.unmodifiableMap(detail);
    }

    public synchronized Map<String, Object> historyList(int pageIndex) {
        List<Map.Entry<String, Map<String, Object>>> entries = new ArrayList<>(historyDetails.entrySet());
        Collections.reverse(entries);
        int pageSize = 20;
        int from = Math.min(entries.size(), Math.max(0, pageIndex - 1) * pageSize);
        int to = Math.min(entries.size(), from + pageSize);
        List<Map<String, Object>> list = new ArrayList<>();
        BigDecimal totalBet = BigDecimal.ZERO;
        BigDecimal totalAward = BigDecimal.ZERO;
        for (Map.Entry<String, Map<String, Object>> entry : entries) {
            Map<String, Object> d = entry.getValue();
            totalBet = totalBet.add(new BigDecimal(d.get("ba").toString()));
            totalAward = totalAward.add(new BigDecimal(d.get("wa").toString()));
        }
        for (Map.Entry<String, Map<String, Object>> entry : entries.subList(from, to)) {
            Map<String, Object> d = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tis", entry.getKey());
            item.put("ba", d.get("ba"));
            item.put("ca", d.get("ca"));
            item.put("wa", d.get("wa"));
            item.put("gt", 1);
            list.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lc", entries.size());
        result.put("ba", totalBet);
        result.put("wa", totalAward);
        result.put("end", to >= entries.size() ? 1 : 0);
        result.put("ll", list);
        return result;
    }

    public synchronized Map<String, Object> historyDetail(String transferId) {
        return historyDetails.get(transferId);
    }

    public long playerId() { return playerId; }
    public String launchToken() { return launchToken; }
    public String runtimeToken() { return runtimeToken; }
    public synchronized BigDecimal balance() { return balance; }
    public synchronized Map<String, Object> last() { return last; }
    public synchronized String lastRoundKey() { return lastLedger == null ? null : lastLedger.roundKey(); }
    public synchronized int deliveryIndex() { return lastLedger == null ? 0 : lastLedger.deliveryIndex(); }
    public synchronized int historyCount() { return historyDetails.size(); }
}
