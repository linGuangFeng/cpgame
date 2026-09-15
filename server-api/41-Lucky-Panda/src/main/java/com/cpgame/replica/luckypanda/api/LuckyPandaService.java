package com.cpgame.replica.luckypanda.api;

import com.hd.pg.appapi.business.vo.cpgame.luckypanda.GameRuleCore;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.RoundClass;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Session, balance, History and complete-Round continuation.
 * A new paid Spin LPOP's one Redis member; cascade/free only project that member.
 */
final class LuckyPandaService {
    private static final int HISTORY_PAGE = 10;
    private final RedisRoundStore rounds;
    private final SecureRandom random;
    private final BigDecimal initialBalance;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(System.currentTimeMillis() << 20);

    LuckyPandaService(RedisRoundStore rounds, BigDecimal initialBalance, SecureRandom random) {
        this.rounds = rounds;
        this.initialBalance = money(initialBalance);
        this.random = random == null ? new SecureRandom() : random;
        if (GameRuleCore.GAME_ID != 41) throw new IllegalStateException("gid must stay 41");
    }

    Map<String, Object> auth(Map<String, String> form, String launchAlias) {
        requireGame(form);
        SessionState state = session(form, launchAlias, true);
        Map<String, Object> player = map("id", state.playerId, "balance", state.balance.toPlainString(), "fbt", 0);
        Map<String, Object> ping = map("enable", true, "seconds", 30);
        return ok(map("player", player, "token", state.token, "ping", ping, "rc", List.of(), "gc", List.of()));
    }

    Map<String, Object> config(Map<String, String> form) {
        SessionState state = session(form, null, false);
        Map<String, Object> data = map(
                "auto", SpinProjector.AUTO,
                "bll", SpinProjector.BET_LEVELS,
                "bsl", SpinProjector.BET_SIZES,
                "cc", "BRL",
                "cs", "R$",
                "dbl", 1,
                "dbs", new BigDecimal("0.02"),
                "spl", SpinProjector.symbolPayList(),
                "ts", Instant.now().getEpochSecond());
        if (state.lastDelivery != null && state.active != null) {
            data.put("last", SpinProjector.lastForConfig(
                    state.lastDelivery, state.active.betSize, state.active.betLevel,
                    displayBalance(state), state.active.stake));
        }
        return ok(data);
    }

    Map<String, Object> spin(Map<String, String> form, String idempotencyHeader) {
        requireGame(form);
        SessionState state = session(form, null, false);
        synchronized (state) {
            String idempotency = first(form.get("idempotency_key"), form.get("request_id"), idempotencyHeader);
            if (idempotency != null && state.idempotent.containsKey(idempotency)) {
                return ok(state.idempotent.get(idempotency));
            }
            if (state.active == null) {
                BigDecimal betSize = decimal(form.get("bs"), "bs");
                int betLevel = integer(form.get("bl"), "bl");
                if (!SpinProjector.legalBet(betSize, betLevel)) throw new ApiException(400, "unsupported bs/bl");
                BigDecimal stake = SpinProjector.stake(betSize, betLevel);
                if (state.balance.compareTo(stake) < 0) throw new ApiException(409, "insufficient balance");
                RedisRoundStore.ClaimedRound claimed;
                try {
                    claimed = rounds.claim(random);
                } catch (IllegalStateException error) {
                    throw new ApiException(503, error.getMessage());
                } catch (Exception error) {
                    throw new ApiException(503, "Redis complete-Round cache unavailable: " + error.getMessage());
                }
                List<SpinProjector.Delivery> deliveries = SpinProjector.flatten(claimed.fact(), betSize, betLevel);
                state.balanceAfterStake = money(state.balance.subtract(stake));
                state.active = new ActiveRound(
                        "41-" + ids.incrementAndGet(),
                        Long.toUnsignedString(ids.incrementAndGet()),
                        claimed, betSize, betLevel, stake, deliveries, 0, Instant.now().getEpochSecond());
            }
            Map<String, Object> body = settleNext(state);
            if (idempotency != null) state.idempotent.put(idempotency, body);
            return ok(body);
        }
    }

    Map<String, Object> historyList(Map<String, String> form) {
        requireGame(form);
        SessionState state = session(form, null, false);
        synchronized (state) {
            int page = form.get("page_index") == null ? 1 : integer(form.get("page_index"), "page_index");
            if (page < 1) throw new ApiException(400, "page_index must be positive");
            long beginAt = optionalEpoch(form.get("begin_at"), Long.MIN_VALUE);
            long endAt = optionalEpoch(form.get("end_at"), Long.MAX_VALUE);
            if (beginAt > endAt) throw new ApiException(400, "begin_at must not exceed end_at");
            List<HistoryRound> ordered = state.history.stream()
                    .filter(value -> value.createdAt >= beginAt && value.createdAt <= endAt)
                    .sorted(Comparator.comparingLong((HistoryRound value) -> value.createdAt).reversed()
                            .thenComparing(value -> value.transferId, Comparator.reverseOrder()))
                    .toList();
            int from = Math.min((page - 1) * HISTORY_PAGE, ordered.size());
            int to = Math.min(from + HISTORY_PAGE, ordered.size());
            List<Map<String, Object>> rows = ordered.subList(from, to).stream().map(this::listRow).toList();
            BigDecimal totalBet = ordered.stream().map(value -> value.stake).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalWin = ordered.stream().map(value -> value.terminalRwa).reduce(BigDecimal.ZERO, BigDecimal::add);
            return ok(map("ba", totalBet.toPlainString(), "wa", totalWin.toPlainString(),
                    "lc", ordered.size(), "ll", rows, "end", to >= ordered.size() ? 1 : 0));
        }
    }

    Map<String, Object> historyDetail(Map<String, String> form) {
        requireGame(form);
        SessionState state = session(form, null, false);
        synchronized (state) {
            String transferId = form.get("transfer_id");
            HistoryRound round = state.history.stream()
                    .filter(value -> value.transferId.equals(transferId))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(404, "history round not found"));
            return ok(detail(round));
        }
    }

    Map<String, Object> balance(Map<String, String> form) {
        SessionState state = session(form, null, false);
        synchronized (state) {
            return ok(map("balance", displayBalance(state).toPlainString(), "cc", "BRL", "cs", "R$"));
        }
    }

    Map<String, Object> ping(Map<String, String> form) {
        session(form, null, false);
        return ok(map("ts", Instant.now().getEpochSecond()));
    }

    Map<String, Object> status() {
        return map("status", "UP", "gameId", GameRuleCore.GAME_ID, "gameName", GameRuleCore.GAME_NAME,
                "rulesHash", GameRuleCore.RULES_HASH, "rulesVersion", GameRuleCore.RULES_VERSION,
                "roundSource", "redis-db15-complete-round", "runtimeDeal", false,
                "managedProcessPid", ProcessHandle.current().pid(),
                "sessionCount", sessions.size());
    }

    private Map<String, Object> settleNext(SessionState state) {
        ActiveRound active = state.active;
        int index = active.nextIndex;
        SpinProjector.Delivery delivery = active.deliveries.get(index);
        boolean terminal = delivery.roundTerminal();
        BigDecimal pb = terminal
                ? money(state.balanceAfterStake.add(delivery.rwa()))
                : state.balanceAfterStake;
        Map<String, Object> body = SpinProjector.spinBody(delivery, active.stake, pb);
        body.put("roundKey", active.roundKey);
        body.put("deliveryIndex", index);
        body.put("deliveryCount", active.deliveries.size());
        body.put("kind", active.claimed.kind().name());
        state.lastDelivery = delivery;
        active.settled.add(new Settled(delivery, pb, Instant.now().getEpochSecond(),
                active.roundKey + "-" + index));
        active.nextIndex++;
        if (terminal) {
            state.balance = pb;
            state.history.add(0, new HistoryRound(
                    active.roundKey, active.transferId, active.betSize, active.betLevel, active.stake,
                    delivery.rwa(), pb, active.createdAt, active.claimed.kind(), List.copyOf(active.settled)));
            state.active = null;
            state.balanceAfterStake = state.balance;
        }
        return body;
    }

    private Map<String, Object> listRow(HistoryRound round) {
        return map("ba", round.stake.toPlainString(), "baf", round.balanceAfter.toPlainString(),
                "bid", round.roundKey, "ca", round.createdAt, "fe", 0, "gm", null,
                "gt", GameRuleCore.GAME_ID, "tis", round.transferId, "wa", round.terminalRwa.toPlainString());
    }

    private Map<String, Object> detail(HistoryRound round) {
        List<Map<String, Object>> paid = new ArrayList<>();
        List<Map<String, Object>> free = new ArrayList<>();
        for (Settled settled : round.steps) {
            Map<String, Object> step = SpinProjector.historyStep(
                    settled.delivery, round.betSize, round.betLevel, settled.pb, settled.bid, settled.createdAt, round.stake);
            if (settled.delivery.nfsc() == 0) paid.add(step);
            else free.add(step);
        }
        Map<String, Object> data = map("baf", round.balanceAfter, "bid", round.roundKey, "bsl", paid);
        if (round.kind == RoundClass.SCATTER_FREE) data.put("fsl", free);
        return data;
    }

    private SessionState session(Map<String, String> form, String launchAlias, boolean create) {
        String token = first(form.get("t"), form.get("token"), launchAlias);
        if (token == null || token.isBlank()) {
            if (!create) throw new ApiException(401, "invalid session token");
            token = "lp41-" + UUID.randomUUID();
        }
        SessionState existing = sessions.get(token);
        if (existing != null) return existing;
        if (!create) throw new ApiException(401, "invalid session token");
        return sessions.computeIfAbsent(token, key -> new SessionState(key, ids.incrementAndGet(), initialBalance));
    }

    private void requireGame(Map<String, String> form) {
        String gid = first(form.get("gid"), form.get("game-id"));
        if (gid != null && !"41".equals(gid)) throw new ApiException(400, "gid must be 41");
    }

    private static BigDecimal displayBalance(SessionState state) {
        return state.active == null ? state.balance : state.balanceAfterStake;
    }

    private static BigDecimal decimal(String value, String name) {
        try { return new BigDecimal(value); }
        catch (RuntimeException error) { throw new ApiException(400, "invalid " + name); }
    }

    private static int integer(String value, String name) {
        try { return Integer.parseInt(value); }
        catch (RuntimeException error) { throw new ApiException(400, "invalid " + name); }
    }

    private static long optionalEpoch(String value, long defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try { return Long.parseLong(value); }
        catch (RuntimeException error) { throw new ApiException(400, "invalid epoch"); }
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static Map<String, Object> ok(Object data) {
        return map("code", 200, "data", data, "info", "ok", "time", Long.toString(Instant.now().getEpochSecond()));
    }

    static Map<String, Object> error(int status, String message) {
        return map("code", status, "data", Map.of(), "info", message, "time", Long.toString(Instant.now().getEpochSecond()));
    }

    private static Map<String, Object> map(Object... fields) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < fields.length; index += 2) values.put((String) fields[index], fields[index + 1]);
        return values;
    }

    private static final class SessionState {
        final String token;
        final long playerId;
        final List<HistoryRound> history = new ArrayList<>();
        final Map<String, Map<String, Object>> idempotent = new LinkedHashMap<>();
        BigDecimal balance;
        BigDecimal balanceAfterStake;
        ActiveRound active;
        SpinProjector.Delivery lastDelivery;

        SessionState(String token, long playerId, BigDecimal balance) {
            this.token = token;
            this.playerId = playerId;
            this.balance = balance;
            this.balanceAfterStake = balance;
        }
    }

    private static final class ActiveRound {
        final String roundKey;
        final String transferId;
        final RedisRoundStore.ClaimedRound claimed;
        final BigDecimal betSize;
        final int betLevel;
        final BigDecimal stake;
        final List<SpinProjector.Delivery> deliveries;
        final List<Settled> settled = new ArrayList<>();
        final long createdAt;
        int nextIndex;

        ActiveRound(String roundKey, String transferId, RedisRoundStore.ClaimedRound claimed,
                    BigDecimal betSize, int betLevel, BigDecimal stake,
                    List<SpinProjector.Delivery> deliveries, int nextIndex, long createdAt) {
            this.roundKey = roundKey;
            this.transferId = transferId;
            this.claimed = claimed;
            this.betSize = betSize;
            this.betLevel = betLevel;
            this.stake = stake;
            this.deliveries = deliveries;
            this.nextIndex = nextIndex;
            this.createdAt = createdAt;
        }
    }

    private record Settled(SpinProjector.Delivery delivery, BigDecimal pb, long createdAt, String bid) { }

    private record HistoryRound(String roundKey, String transferId, BigDecimal betSize, int betLevel,
                                BigDecimal stake, BigDecimal terminalRwa, BigDecimal balanceAfter,
                                long createdAt, RoundClass kind, List<Settled> steps) { }
}
