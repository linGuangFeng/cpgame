package com.cpgame.saci.server;

import com.cpgame.saci.generator.GameRules;
import com.cpgame.saci.generator.ResultUtil;
import com.cpgame.saci.generator.model.RoundMode;
import com.cpgame.saci.generator.model.RoundResult;
import com.cpgame.saci.generator.model.SpinStep;

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
    private final Map<String, Integer> wnl = new LinkedHashMap<>();
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
        Map<String, Object> last = new LinkedHashMap<>();
        last.put("afnl", List.of());
        last.put("ba", 0);
        last.put("bl", 1);
        last.put("bs", SpinStep.amount(new BigDecimal("0.02")));
        last.put("ca", Instant.now().getEpochSecond());
        last.put("frwa", 0);
        last.put("fsn", 0);
        last.put("gm", 1);
        last.put("gt", 1);
        last.put("nfsc", 0);
        last.put("nrsc", 0);
        last.put("pb", SpinStep.moneyText(balance));
        last.put("rskl", new ArrayList<>(GameRules.IDLE_BOARD));
        last.put("rsn", 0);
        last.put("rwa", 0);
        last.put("small_game_type", 0);
        last.put("ss", 1);
        last.put("syxl", List.of());
        last.put("wa", 0);
        last.put("wmkl", List.of());
        last.put("wnl", Map.of());
        last.put("wskl", List.of());
        last.put("wn", 0);
        return Collections.unmodifiableMap(last);
    }

    private ResultUtil.EnergyState energyFor(int bl, BigDecimal bs) {
        String key = GameRules.energyKey(GameRules.betAmount(bl, bs));
        return new ResultUtil.EnergyState(wnl.getOrDefault(key, 0));
    }

    private void commitEnergy(int bl, BigDecimal bs, RoundResult round) {
        String key = GameRules.energyKey(GameRules.betAmount(bl, bs));
        if (round.mode() == RoundMode.FREE_SPINS) return;
        int wn = round.mode() == RoundMode.WILD_VORTEX ? 0 : round.steps().get(round.steps().size() - 1).wn();
        if (wn <= 0) wnl.remove(key);
        else wnl.put(key, wn);
    }

    private Map<String, Object> decorate(Map<String, Object> data, int bl, BigDecimal bs) {
        Map<String, Object> copy = new LinkedHashMap<>(data);
        Map<String, Integer> map = new LinkedHashMap<>(wnl);
        int wn = data.get("wn") instanceof Number n ? n.intValue() : 0;
        String key = GameRules.energyKey(GameRules.betAmount(bl, bs));
        if (wn > 0) map.put(key, wn);
        else map.remove(key);
        copy.put("wnl", map);
        return copy;
    }

    public synchronized Map<String, Object> spin(BigDecimal bs, int bl, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Cached cached = idempotentResponses.get(idempotencyKey);
            if (cached != null) return cached.data();
        }
        if (pendingRound == null) {
            BigDecimal bet = GameRules.betAmount(bl, bs);
            if (balance.compareTo(bet) < 0) throw new IllegalStateException("INSUFFICIENT_BALANCE");
            RoundResult claimed = roundStore.claim(bs, bl, balance, energyFor(bl, bs));
            ResultUtil.analyze(claimed);
            pendingRound = claimed;
            nextDeliveryIndex = 0;
        } else if (pendingRound.bl() != bl || pendingRound.bs().compareTo(bs) != 0) {
            throw new IllegalArgumentException("活动 Round 必须沿用触发时的 bl/bs");
        }
        SpinStep step = pendingRound.steps().get(nextDeliveryIndex);
        int deliveryIndex = nextDeliveryIndex;
        balance = step.pb();
        Map<String, Object> painted = decorate(step.protocolMap(), bl, bs);
        last = Collections.unmodifiableMap(new LinkedHashMap<>(painted));
        nextDeliveryIndex++;
        if (step.roundTerminal()) {
            commitEnergy(bl, bs, pendingRound);
            last = Collections.unmodifiableMap(decorate(painted, bl, bs));
            history.add(new HistoryRecord(pendingRound, Instant.now().getEpochSecond()));
            pendingRound = null;
            nextDeliveryIndex = 0;
        }
        Map<String, Object> data = new LinkedHashMap<>(last);
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
            this.bid = "61-" + round.roundKey();
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
            List<Map<String, Object>> bsl = new ArrayList<>();
            List<Map<String, Object>> fsl = new ArrayList<>();
            List<Map<String, Object>> rsl = new ArrayList<>();
            for (SpinStep step : round.steps()) {
                Map<String, Object> row = step.historyStep(bid, createdAt);
                if (step.gm() == 2) fsl.add(row);
                else if (step.gm() == 3) rsl.add(row);
                else bsl.add(row);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("baf", sessionBalance);
            result.put("bid", bid);
            result.put("bsl", bsl);
            if (!fsl.isEmpty()) result.put("fsl", fsl);
            if (!rsl.isEmpty()) result.put("rsl", rsl);
            return result;
        }
    }
}
