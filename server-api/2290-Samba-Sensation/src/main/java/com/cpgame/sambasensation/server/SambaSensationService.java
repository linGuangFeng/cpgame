package com.cpgame.sambasensation.server;

import com.cpgame.sambasensation.core.GameRuleCore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class SambaSensationService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicLong OIDS = new AtomicLong(System.currentTimeMillis() * 1000L);
    // An uncharged entry screen must not require a populated paid-result pool.
    private static final GameRuleCore.CompleteRoundFact IDLE = createIdle();
    private static GameRuleCore.CompleteRoundFact createIdle() {
        var generator = new com.cpgame.sambasensation.generator.RuntimeSpinGenerator(DemoRuntimePolicy.generatorParameters());
        var page = generator.paidPage(new SecureRandom(), 1, 0, 0, 0, true);
        var fact = new GameRuleCore.CompleteRoundFact(GameRuleCore.EntryKind.PAID_INITIAL, 1, 0,
                new int[GameRuleCore.COIN_SLOTS], GameRuleCore.CoinTransition.UNCHANGED, 0,
                List.of(new GameRuleCore.Step(page.boards())));
        GameRuleCore.validateStructure(fact);
        if (com.cpgame.sambasensation.core.ResultUtil.evaluate(fact).multiplier() != 0)
            throw new IllegalStateException("idle screen must have no award");
        return fact;
    }
    private final RedisRoundStore rounds;
    private final BigDecimal initialBalance;
    private final SecureRandom random;
    private final RuntimeRoundComposer runtimeComposer;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    SambaSensationService(RedisRoundStore rounds, BigDecimal initialBalance, SecureRandom random) {
        this(rounds, initialBalance, random, null);
    }

    SambaSensationService(RedisRoundStore rounds, BigDecimal initialBalance, SecureRandom random,
                           RuntimeRoundComposer runtimeComposer) {
        SharedRuleCoreContract.verify();
        this.rounds = rounds;
        this.initialBalance = SpinProjector.money(initialBalance);
        this.random = random;
        this.runtimeComposer = runtimeComposer;
    }

    ObjectNode init(Map<String, String> form) throws IOException {
        SessionState state = session(form);
        synchronized (state) {
            ObjectNode data;
            if (state.lastDelivery != null) {
                data = state.lastDelivery.deepCopy();
            } else {
                GameRuleCore.CompleteRoundFact idle = IDLE;
                BigDecimal bet = new BigDecimal("0.02");
                int level = 10;
                data = SpinProjector.idleData(JSON, idle, state.collection, state.balance, bet, level,
                        "INIT-2290-" + state.numericId);
            }
            return ok(data);
        }
    }

    ObjectNode spin(Map<String, String> form, String headerIdempotencyKey) throws IOException {
        requireGame(form);
        SessionState state = session(form);
        String idempotencyKey = firstNonBlank(headerIdempotencyKey, form.get("idempotency_key"), form.get("request_id"));
        synchronized (state) {
            if (idempotencyKey != null) {
                ObjectNode cached = state.idempotent.get(idempotencyKey);
                if (cached != null) return cached.deepCopy();
            }
            // 原页面的自动 Free 续局可能省略 type。这里只依据 Session 已持有的完整局状态
            // 还原为 type=2；它不选择结果、不领取新 member，也不会重新抽倍率。
            String rawType = form.get("type");
            int type = rawType == null || rawType.isBlank()
                    ? (state.active != null ? 2 : 1)
                    : integer(rawType, "type");
            ObjectNode response;
            if (state.active == null) {
                if (type != 1 && type != 3) throw new IllegalArgumentException("type=2 requires an active Free Round");
                BigDecimal bet = decimal(form.getOrDefault("bet", "0.02"), "bet");
                int level = integer(form.getOrDefault("level", "10"), "level");
                int betType = integer(form.getOrDefault("bet_type", "1"), "bet_type");
                if (!SpinProjector.legalBet(bet, level, betType)) throw new IllegalArgumentException("unsupported bet/level/bet_type");
                boolean featureBuy = type == 3;
                BigDecimal charge = SpinProjector.charge(bet, level, betType, featureBuy);
                if (state.balance.compareTo(charge) < 0) throw new IllegalArgumentException("insufficient balance");
                RedisRoundStore.ClaimedRound claimed = featureBuy
                        ? rounds.claimFeatureBuy(random, state.collection)
                        : runtimeComposer == null
                            ? rounds.claimPaid(random, betType, state.collection)
                            : runtimeComposer.composePaid(random, betType, state.collection);
                if (!featureBuy && claimed.fact().betType() != betType) throw new IllegalStateException("claimed member bet_type mismatch");
                List<SpinProjector.Delivery> deliveries = SpinProjector.project(claimed.fact(), bet, level, featureBuy);
                GameRuleCore.CollectionProjection collection = GameRuleCore.applyCollectionTransition(state.collection, claimed.fact());
                String parentOid = Long.toUnsignedString(OIDS.incrementAndGet());
                state.active = new ActiveRound(claimed, bet, level, betType, featureBuy, charge,
                        parentOid, deliveries, state.balance, collection);
                response = settleNext(state, type);
            } else {
                if (type != 2) throw new IllegalArgumentException("active Free Round must continue with type=2");
                response = settleNext(state, 2);
            }
            if (idempotencyKey != null) remember(state, idempotencyKey, response);
            return response.deepCopy();
        }
    }

    private ObjectNode settleNext(SessionState state, int requestType) {
        ActiveRound active = state.active;
        int index = active.nextIndex;
        if (index >= active.deliveries.size()) throw new IllegalStateException("complete Round already terminated");
        if ((index == 0 && requestType == 2) || (index > 0 && requestType != 2)) {
            throw new IllegalArgumentException("request type violates complete Round sequence");
        }
        SpinProjector.Delivery delivery = active.deliveries.get(index);
        BigDecimal charged = index == 0 ? active.charge : BigDecimal.ZERO.setScale(2);
        BigDecimal start = state.balance;
        state.balance = SpinProjector.money(start.subtract(charged).add(delivery.win()));
        String oid = index == 0 ? active.parentOid : Long.toUnsignedString(OIDS.incrementAndGet());
        ObjectNode data = SpinProjector.responseData(JSON, active.claimed.fact(), delivery,
                active.collection, active.bet, active.level, requestType, charged, start, state.balance, oid, active.parentOid);
        // gameResult 必须严格编码回原厂字段；完整局游标只保存在 Session，不能泄露为自创响应字段。
        active.responses.add(data.deepCopy());
        active.totalAward = SpinProjector.money(active.totalAward.add(delivery.win()));
        active.nextIndex++;
        if (index == 0) state.collection = active.collection.nextState();
        state.lastDelivery = data.deepCopy();
        ObjectNode response = ok(data);
        if (active.nextIndex == active.deliveries.size()) {
            state.history.add(historyRecord(active, state.balance));
            state.active = null;
        }
        return response;
    }

    ObjectNode historySummary(Map<String, String> form) {
        SessionState state = session(form);
        synchronized (state) {
            ObjectNode data = JSON.createObjectNode();
            ArrayNode list = data.putArray("list");
            BigDecimal totalBet = BigDecimal.ZERO, totalChange = BigDecimal.ZERO;
            long today = today();
            for (int offset = 0; offset < 7; offset++) {
                long day = today - offset * 86400L;
                BigDecimal bet = BigDecimal.ZERO, change = BigDecimal.ZERO;
                for (ObjectNode row : state.history) {
                    if (row.path("day").asLong() == day) {
                        bet = bet.add(row.path("bet_gold").decimalValue());
                        change = change.add(row.path("change_gold").decimalValue());
                    }
                }
                list.addObject().put("day", day).put("bet_gold", SpinProjector.money(bet)).put("change_gold", SpinProjector.money(change));
                totalBet = totalBet.add(bet);
                totalChange = totalChange.add(change);
            }
            data.putObject("statistics").put("total_bet_gold", SpinProjector.money(totalBet)).put("total_change_gold", SpinProjector.money(totalChange));
            return ok(data);
        }
    }

    ObjectNode historyDetail(Map<String, String> form) {
        SessionState state = session(form);
        int page = positive(form.getOrDefault("page", "1"), "page");
        int pageSize = positive(form.getOrDefault("page_size", "30"), "page_size");
        synchronized (state) {
            List<ObjectNode> ordered = state.history.stream()
                    .sorted(Comparator.comparingLong(n -> -n.path("time").asLong())).toList();
            Totals totals = totals(ordered);
            ObjectNode data = historyBase(totals);
            int from = Math.min(ordered.size(), (page - 1) * pageSize);
            int to = Math.min(ordered.size(), from + pageSize);
            ArrayNode list = data.withArray("list");
            for (ObjectNode item : ordered.subList(from, to)) list.add(item.deepCopy());
            return ok(data);
        }
    }

    ObjectNode balance(Map<String, String> form) {
        SessionState state = session(form);
        synchronized (state) { return ok(JSON.createObjectNode().put("balance", state.balance).put("gold", state.balance)); }
    }

    ObjectNode user(Map<String, String> form) {
        SessionState state = session(form);
        synchronized (state) {
            ObjectNode data = JSON.createObjectNode();
            data.put("currency_symbol", "R$").put("day_first_login", 0).putNull("first_gold")
                    .put("gid", 2290).put("gold", state.balance).put("is_guide", 0)
                    .put("nickname", "Samba Sensation Demo").put("token", state.key)
                    .put("total_recharge", "0").put("uid", state.numericId).putArray("user_config");
            return ok(data);
        }
    }

    ObjectNode config(Map<String, String> form, String host, int port) {
        String language = normalizeLanguage(firstNonBlank(form.get("language"), form.get("l"), "en-us"));
        ObjectNode data = JSON.createObjectNode();
        data.putObject("game_address").putObject("ship_address_config");
        ObjectNode game = data.putObject("game_info");
        ArrayNode bets = game.putArray("bet_gold");
        SpinProjector.BET_SIZES.forEach(bets::add);
        game.put("buy_free_max_bet", 0).put("default_bet_gold", 0).put("default_level", 10)
                .put("gid", 2290).put("least_gold", 0).put("name", "Samba Sensation").put("status", "1");
        game.putArray("game_way").addObject().put("max_bet_gold", "0.00").put("min_bet_gold", "0.00")
                .put("way_id", 229010000).put("win_multi", "1.00");
        ObjectNode server = data.putObject("game_server");
        server.put("ngs_switch", 0).put("gs_host1", host).put("gs_port1", Integer.toString(port))
                .put("gs_sport1", "0").put("gs_push_host", host).put("gs_push_port", Integer.toString(port))
                .put("gs_push_sport", "0").put("gos_host", host).put("gos_port", Integer.toString(port))
                .put("gos_sport", "0").put("ps_host", host).put("ps_port", Integer.toString(port));
        ObjectNode initial = data.putObject("initial_config");
        initial.put("bd_bet_count", 2).put("current_sys_time", Instant.now().getEpochSecond())
                .put("is_debug", false).put("is_stopgs", 0).put("user_on_hook_time", 600).put("version", 1745909504);
        data.put("language", language).put("r", 1).put("zone", 0);
        return ok(data);
    }

    ObjectNode activity() {
        ObjectNode free = JSON.createObjectNode();
        free.putArray("act_list");
        free.put("invite_act_have", 0).put("invite_end_time", 0);
        ObjectNode data = JSON.createObjectNode(); data.set("free", free); return ok(data);
    }

    ObjectNode status(Map<String, String> form) {
        SessionState state = session(form);
        synchronized (state) {
            ObjectNode data = JSON.createObjectNode();
            data.put("code", 0).put("gameId", 2290).put("rulesHash", SharedRuleCoreContract.RULES_HASH)
                    .put("controllerContractVersion", 3).put("demoReadsRedisCache", true)
                    .put("selectsWinOrLossThenMultiplier", runtimeComposer == null)
                    .put("completeRoundsPreloaded", runtimeComposer == null)
                    .put("ordinaryLossUsesRuntimeGenerator", runtimeComposer != null)
                    .put("runtimeCollectionIncrements", runtimeComposer != null)
                    .put("cachedFreeTailComposition", runtimeComposer != null)
                    .put("ordinaryWinsUseRedisCache", runtimeComposer != null)
                    .put("normalCacheUsesFloorPrefixes", runtimeComposer != null)
                    .put("payoutSelectionIsUpperBound", runtimeComposer != null)
                    .put("runtimeZeroMultiplierPage", runtimeComposer != null)
                    .put("featureBuyUsesCachedCompleteRound", true)
                    .put("runtimeDealWhenCacheEmpty", false)
                    .put("session", state.key).put("balance", state.balance).put("historyCount", state.history.size());
            ArrayNode ids = data.putArray("behaviorIds"); SharedRuleCoreContract.BEHAVIOR_IDS.forEach(ids::add);
            if (state.active != null) data.put("roundKey", state.active.parentOid)
                    .put("nextDeliveryIndex", state.active.nextIndex).put("deliveryCount", state.active.deliveries.size());
            data.put("scatterProgress", state.collection.scatterProgress());
            ArrayNode coins = data.putArray("coinVector");
            for (int value : state.collection.coins()) coins.add(value);
            data.put("coinResetPending", state.collection.resetPending());
            return data;
        }
    }

    private static ObjectNode historyRecord(ActiveRound active, BigDecimal endBalance) {
        ObjectNode row = active.responses.get(0).deepCopy();
        long time = Instant.now().getEpochSecond();
        row.put("order_id", active.parentOid + "-2290").put("time", time).put("day", today());
        row.put("bet_gold", active.charge).put("change_gold", SpinProjector.money(active.totalAward.subtract(active.charge)))
                .put("start_gold", active.startBalance).put("end_gold", endBalance);
        row.put("odds", active.totalAward.divide(SpinProjector.unitBetGold(active.bet, active.level), 6, RoundingMode.HALF_UP));
        ObjectNode extend = row.putObject("extend"); extend.put("act_bet_gold", 0).put("act_id", "0").put("act_type", 0);
        ArrayNode results = row.putArray("results");
        for (ObjectNode source : active.responses) {
            ObjectNode copy = source.deepCopy();
            copy.put("day", today());
            copy.put("bet", false);
            if (copy.path("result").isMissingNode() && !copy.path("props").isMissingNode())
                copy.set("result", copy.path("props").deepCopy());
            if (copy.path("extend").isMissingNode())
                copy.putObject("extend").put("act_bet_gold", 0).put("act_id", "0").put("act_type", 0);
            results.add(copy);
        }
        return row;
    }

    private static Totals totals(List<ObjectNode> history) {
        BigDecimal bet = BigDecimal.ZERO, change = BigDecimal.ZERO;
        for (ObjectNode row : history) {
            bet = bet.add(row.path("bet_gold").decimalValue());
            change = change.add(row.path("change_gold").decimalValue());
        }
        return new Totals(SpinProjector.money(bet), SpinProjector.money(change));
    }
    private static ObjectNode historyBase(Totals totals) {
        ObjectNode data = JSON.createObjectNode(); data.putArray("list");
        data.putObject("statistics").put("total_bet_gold", totals.bet()).put("total_change_gold", totals.change());
        return data;
    }
    private SessionState session(Map<String, String> form) {
        String key = firstNonBlank(form.get("token"), form.get("t"), "local-replay");
        return sessions.computeIfAbsent(key, value -> new SessionState(value, initialBalance));
    }
    private static void requireGame(Map<String, String> form) {
        String raw = form.get("gid");
        if (raw != null && !raw.isBlank() && !"2290".equals(raw)) throw new IllegalArgumentException("gid must be 2290");
    }
    private static void remember(SessionState state, String key, ObjectNode response) {
        state.idempotent.put(key, response.deepCopy());
        while (state.idempotent.size() > 256) state.idempotent.remove(state.idempotent.keySet().iterator().next());
    }
    static ObjectNode ok(ObjectNode data) {
        ObjectNode response = JSON.createObjectNode();
        response.put("code", 0); response.set("data", data); response.put("msg", "success");
        response.put("time", Long.toString(Instant.now().getEpochSecond()));
        return response;
    }
    static ObjectNode error(int code, String message) {
        return JSON.createObjectNode().put("code", code).put("msg", message)
                .put("time", Long.toString(Instant.now().getEpochSecond()));
    }
    private static long today() { return Instant.now().getEpochSecond() / 86400 * 86400; }
    private static String normalizeLanguage(String value) {
        String normalized = value.toLowerCase().replace('_', '-');
        return switch (normalized) {
            case "bn", "bn-bd" -> "bn-bd"; case "en", "en-us" -> "en-us";
            case "es", "es-es" -> "es-es"; case "fr", "fr-fr" -> "fr-fr";
            case "id", "id-id" -> "id-id"; case "ko", "ko-ko" -> "ko-ko";
            case "pt", "pt-br", "pt-pt" -> "pt-pt"; case "th", "th-th" -> "th-th";
            case "tr", "tr-tr" -> "tr-tr"; default -> "en-us";
        };
    }
    private static BigDecimal decimal(String value, String name) {
        try { BigDecimal result = new BigDecimal(value); if (result.signum() <= 0) throw new NumberFormatException(); return result; }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(name + " must be positive decimal"); }
    }
    private static int integer(String value, String name) {
        try { return Integer.parseInt(value); } catch (NumberFormatException ex) { throw new IllegalArgumentException(name + " must be integer"); }
    }
    private static int positive(String value, String name) { int result = integer(value, name); if (result <= 0) throw new IllegalArgumentException(name + " must be positive"); return result; }
    private static String firstNonBlank(String... values) { for (String value : values) if (value != null && !value.isBlank()) return value; return null; }

    private record Totals(BigDecimal bet, BigDecimal change) { }
    private static final class SessionState {
        final String key; final long numericId; BigDecimal balance; ActiveRound active; ObjectNode lastDelivery;
        GameRuleCore.CollectionState collection = GameRuleCore.CollectionState.initial();
        final List<ObjectNode> history = new ArrayList<>();
        final Map<String, ObjectNode> idempotent = new LinkedHashMap<>();
        SessionState(String key, BigDecimal balance) {
            this.key = key; this.numericId = Integer.toUnsignedLong(key.hashCode()); this.balance = balance;
        }
    }
    private static final class ActiveRound {
        final RedisRoundStore.ClaimedRound claimed; final BigDecimal bet; final int level; final int betType;
        final boolean featureBuy; final BigDecimal charge; final String parentOid;
        final List<SpinProjector.Delivery> deliveries; final BigDecimal startBalance;
        final GameRuleCore.CollectionProjection collection;
        final List<ObjectNode> responses = new ArrayList<>();
        int nextIndex; BigDecimal totalAward = BigDecimal.ZERO.setScale(2);
        ActiveRound(RedisRoundStore.ClaimedRound claimed, BigDecimal bet, int level, int betType,
                    boolean featureBuy, BigDecimal charge, String parentOid,
                    List<SpinProjector.Delivery> deliveries, BigDecimal startBalance,
                    GameRuleCore.CollectionProjection collection) {
            this.claimed = claimed; this.bet = bet; this.level = level; this.betType = betType;
            this.featureBuy = featureBuy; this.charge = charge; this.parentOid = parentOid;
            this.deliveries = deliveries; this.startBalance = startBalance;
            this.collection = collection;
        }
    }
}
