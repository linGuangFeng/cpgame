package com.cpgame.luckycatii.server;

import com.cpgame.luckycatii.ResultUtil;
import com.cpgame.luckycatii.model.RoundResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class PlayerSession {
    private final long playerId;
    private final String token;
    private final RedisRoundStore roundStore;
    private final AtomicLong transferSeq;
    private BigDecimal balance;
    private Map<String, Object> last;
    private final Map<String, Map<String, Object>> idempotentResponses = new LinkedHashMap<>();
    private final List<HistoryRow> history = new ArrayList<>();

    PlayerSession(long playerId, String token, BigDecimal balance, RedisRoundStore roundStore, AtomicLong transferSeq) {
        this.playerId = playerId;
        this.token = token;
        this.balance = balance;
        this.roundStore = roundStore;
        this.transferSeq = transferSeq;
        this.last = null;
    }

    public synchronized Map<String, Object> spin(BigDecimal bs, int bl, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Map<String, Object> cached = idempotentResponses.get(idempotencyKey);
            if (cached != null) return cached;
        }
        BigDecimal bet = com.cpgame.luckycatii.GameRules.betAmount(bs, bl);
        if (balance.compareTo(bet) < 0) throw new IllegalStateException("INSUFFICIENT_BALANCE");
        RoundResult round = roundStore.claim(bs, bl);
        ResultUtil.verify(round);
        BigDecimal post = balance.subtract(round.betAmount()).add(round.award());
        String tis = Long.toUnsignedString(transferSeq.incrementAndGet());
        long ca = System.currentTimeMillis() / 1000L;
        Map<String, Object> response = SpinWireMapper.spinData(round, post);
        balance = post;
        last = SpinWireMapper.configLast(round, post, ca);
        history.add(new HistoryRow(tis, ca, round, post));
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotentResponses.put(idempotencyKey, Collections.unmodifiableMap(new LinkedHashMap<>(response)));
        }
        return response;
    }

    public synchronized Map<String, Object> historyList(int pageIndex, long beginAt, long endAt) {
        List<HistoryRow> filtered = history.stream()
                .filter(row -> beginAt == 0 || row.createdAt >= beginAt)
                .filter(row -> endAt == 0 || row.createdAt <= endAt)
                .sorted(Comparator.comparingLong(HistoryRow::createdAt).reversed()
                        .thenComparing(HistoryRow::tis, Comparator.reverseOrder()))
                .toList();
        int page = Math.max(1, pageIndex);
        int from = Math.min(filtered.size(), (page - 1) * 20);
        int to = Math.min(filtered.size(), from + 20);
        List<Object> records = new ArrayList<>();
        BigDecimal totalBet = BigDecimal.ZERO;
        BigDecimal totalAward = BigDecimal.ZERO;
        for (HistoryRow row : filtered) {
            totalBet = totalBet.add(row.round.betAmount());
            totalAward = totalAward.add(row.round.award());
        }
        for (HistoryRow row : filtered.subList(from, to)) {
            records.add(SpinWireMapper.historyRecord(row.round, row.balanceAfter, row.createdAt, row.tis));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("end", to < filtered.size() ? 0 : 1);
        data.put("lc", filtered.size());
        data.put("ba", SpinWireMapper.moneyString(totalBet));
        data.put("wa", SpinWireMapper.moneyString(totalAward));
        data.put("ll", records);
        return data;
    }

    public synchronized Map<String, Object> historyDetail(String transferId) {
        return history.stream().filter(row -> row.tis.equals(transferId)).findFirst()
                .map(row -> SpinWireMapper.historyDetail(row.round, row.balanceAfter, row.createdAt, row.tis))
                .orElse(null);
    }

    public long playerId() { return playerId; }
    public String token() { return token; }
    public synchronized BigDecimal balance() { return balance; }
    public synchronized Map<String, Object> last() { return last; }
    public synchronized int historyCount() { return history.size(); }

    private record HistoryRow(String tis, long createdAt, RoundResult round, BigDecimal balanceAfter) {}
}
