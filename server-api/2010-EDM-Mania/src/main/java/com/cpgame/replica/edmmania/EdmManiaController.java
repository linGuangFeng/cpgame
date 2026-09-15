package com.cpgame.replica.edmmania;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Controller v3：静态与 API 共用唯一端口。完整局只从 Redis db15 领取，禁止当场出牌。
 */
public final class EdmManiaController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, SessionState> SESSIONS = new ConcurrentHashMap<>();
    private static final BigDecimal[] BET_SIZES = {
            new BigDecimal("0.02"), new BigDecimal("0.1"), new BigDecimal("0.2")
    };
    private static final int WAYS = 20;
    /** Game2010Constant.buyFreeTimesBetAmountMultiple：购买成本 = 当前总押注 × 75。 */
    private static final int BUY_MULTIPLE = 75;

    private final Path publishRoot;
    private final BigDecimal initialBalance;
    private final int maxConsecutiveWins;
    private final int maxMarySpins;
    private final double lossProbability;
    private final RedisPool redis;

    private EdmManiaController(Path publishRoot, Properties config, RedisPool redis) {
        this.publishRoot = publishRoot.toAbsolutePath().normalize();
        this.initialBalance = new BigDecimal(config.getProperty("session.initial-balance", "10000.00"))
                .setScale(2, RoundingMode.HALF_UP);
        this.maxConsecutiveWins = Integer.parseInt(config.getProperty("round.max-consecutive-wins", "12"));
        this.maxMarySpins = Integer.parseInt(config.getProperty("round.max-mary-spins", "30"));
        this.lossProbability = Double.parseDouble(config.getProperty("round.loss-probability", "0.815"));
        this.redis = redis;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        int port = requiredPort(options);
        Properties config = loadConfig(options.get("config"));
        Path publish = resolvePublish(options.getOrDefault("publish", config.getProperty("publish.directory", "")));
        RedisPool redis = RedisPool.create(config);
        EdmManiaController controller = new EdmManiaController(publish, config, redis);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", controller::handle);
        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            try { redis.close(); } catch (IOException ignored) { }
        }, "edm-mania-controller-stop"));
        server.start();
        System.out.printf("CONTROLLER_READY gameId=2010 port=%d pid=%d rulesVersion=%s rulesHash=%s publish=%s redis=%s:%s db=%s%n",
                port, ProcessHandle.current().pid(), EdmManiaRulesMetadata.VERSION, EdmManiaRulesMetadata.HASH,
                publish, config.getProperty("redis.host", "18.234.101.161"),
                config.getProperty("redis.port", "8021"),
                config.getProperty("redis.database", "0"));
        try { redis.ensure(); }
        catch (Exception ex) {
            System.err.println("[WARN] Redis not ready at startup: " + ex.getMessage() + " — HTTP is up; spin will fail if still empty.");
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) { sendEmpty(exchange, 204); return; }
            String path = exchange.getRequestURI().getPath();
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod()) || "HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                if ("/api/balance".equals(path)) { sendJson(exchange, ok().set("data", balanceData(session(exchange)))); return; }
                if ("/api/session".equals(path)) { sendJson(exchange, ok().set("data", sessionData(session(exchange)))); return; }
                serveStatic(exchange); return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { sendError(exchange, 405, "method not allowed"); return; }
            Map<String, String> form = form(exchange);
            SessionState session = session(exchange, form);
            switch (path) {
                case "/cp/config/initialData" -> sendJson(exchange, configResponse(exchange, form));
                case "/cp/account/getUserInfo" -> sendJson(exchange, userResponse(exchange, session));
                case "/cp/activity/getActivity" -> sendJson(exchange, activityResponse());
                case "/cp/single_game.Game/initRoom" -> sendJson(exchange, initResponse(session));
                case "/cp/single_game.Game/gameResult" -> sendJson(exchange, spinResponse(exchange, session, form));
                case "/cp/goldgame/single_game_user_gold_history" -> sendJson(exchange, historySummary(session));
                case "/cp/goldgame/single_game_user_history" -> sendJson(exchange, historyDetail(session, form));
                case "/api/balance" -> sendJson(exchange, ok().set("data", balanceData(session)));
                case "/api/session" -> sendJson(exchange, ok().set("data", sessionData(session)));
                default -> sendError(exchange, 404, "unknown endpoint: " + path);
            }
        } catch (IllegalStateException ex) {
            sendError(exchange, 503, ex.getMessage());
        } catch (IllegalArgumentException ex) {
            sendError(exchange, 400, ex.getMessage());
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
            sendError(exchange, 500, "controller failure");
        } finally {
            exchange.close();
        }
    }

    private ObjectNode spinResponse(HttpExchange exchange, SessionState state, Map<String, String> form) throws Exception {
        synchronized (state) {
            String idempotencyKey = firstNonBlank(exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                    form.get("idempotency_key"), form.get("request_id"));
            if (idempotencyKey != null && state.idempotent.containsKey(idempotencyKey)) {
                return state.idempotent.get(idempotencyKey).deepCopy();
            }
            boolean featureBuy = state.active == null && "3".equals(form.get("bet_type"));
            ParsedBet bet = parseBet(form, featureBuy);
            if (state.active == null) {
                if (state.balance.compareTo(bet.charged) < 0) throw new IllegalArgumentException("insufficient balance");
                state.active = claimRound(bet);
            }
            ObjectNode settled = settleNext(state, bet);
            ObjectNode response = ok(); response.set("data", settled);
            if (idempotencyKey != null) state.idempotent.put(idempotencyKey, response.deepCopy());
            return response;
        }
    }

    private ActiveRound claimRound(ParsedBet bet) throws Exception {
        CompleteRoundCodec codec = new CompleteRoundCodec();
        IllegalStateException last = new IllegalStateException("PREGENERATED_CACHE_EMPTY");
        int attempts = bet.featureBuy ? 48 : 12;
        for (int attempt = 0; attempt < attempts; attempt++) {
            RedisPool.DrawnRound drawn = bet.featureBuy ? redis.takeSpecial(RANDOM) : redis.take(RANDOM, lossProbability);
            try {
                if (bet.featureBuy && drawn.fact().spins().size() < 2) {
                    last = new IllegalStateException("feature-buy Redis member is not a free-feature round");
                    continue;
                }
                CompleteRoundFact fact = bet.featureBuy
                        ? new CompleteRoundFact(drawn.fact().v(), true, drawn.fact().spins())
                        : drawn.fact();
                RoundVerification verification = codec.verify(codec.encode(fact), maxConsecutiveWins, fact.featureBuy(), maxMarySpins);
                if (verification.multiplier().intValueExact() != drawn.ratio()) {
                    last = new IllegalStateException("Redis member failed independent ResultUtil");
                    continue;
                }
                ArrayNode templates = (ArrayNode) JSON.readTree(EdmManiaRoundProtocolCli.protocol(
                        fact, bet.unitBet, bet.level, RANDOM.nextLong()));
                List<ObjectNode> deliveries = new ArrayList<>();
                templates.forEach(node -> {
                    ObjectNode value = (ObjectNode) node.deepCopy();
                    value.remove("_seed");
                    deliveries.add(value);
                });
                return new ActiveRound(UUID.randomUUID().toString(), List.copyOf(deliveries), 0, bet);
            } catch (IllegalArgumentException ex) {
                last = new IllegalStateException(ex.getMessage() == null ? "Redis member failed independent ResultUtil" : ex.getMessage());
            }
        }
        throw last;
    }

    private ObjectNode settleNext(SessionState state, ParsedBet bet) {
        ActiveRound active = state.active;
        int deliveryIndex = active.nextIndex;
        ObjectNode value = active.deliveries.get(deliveryIndex).deepCopy();
        value.remove("_unit_bet");
        boolean featureBuy = value.remove("_feature_buy").asBoolean();
        int freeIndex = value.remove("_free_index").asInt();
        int freeTotal = value.remove("_free_total").asInt();
        BigDecimal cumulative = value.remove("_cumulative_free_win").decimalValue();
        int endingMultiplier = value.remove("_ending_multiplier").asInt();
        int awarded = value.remove("_awarded_free_spins").asInt();
        value.remove("_rulesVersion"); value.remove("_rulesHash");
        BigDecimal charged = freeIndex == 0 ? active.bet.charged : BigDecimal.ZERO;
        BigDecimal spinWin = value.path("total_win").decimalValue();
        BigDecimal start = state.balance;
        state.balance = start.subtract(charged).add(spinWin).setScale(2, RoundingMode.HALF_UP);
        value.put("order_id", value.path("oid").asText() + "-2010");
        if (!value.has("time") || value.path("time").asLong() <= 0) {
            value.put("time", Instant.now().getEpochSecond());
        }
        value.put("bet_gold", charged);
        value.put("change_gold", spinWin.subtract(charged));
        value.put("start_gold", start);
        value.put("end_gold", state.balance);
        value.put("odds", charged.signum() == 0 ? (spinWin.signum() == 0 ? BigDecimal.ZERO : spinWin)
                : spinWin.divide(charged, 6, RoundingMode.HALF_UP));
        ObjectNode extend = value.putObject("extend");
        extend.put("act_bet_gold", 0).put("act_id", "0").put("bet_type", featureBuy ? 3 : 0);
        int type = value.path("type").asInt();
        if (freeTotal == 0 && type == 1) {
            value.put("frees", false);
        } else {
            ObjectNode frees = value.putObject("frees");
            int remaining = Math.max(0, freeTotal - freeIndex);
            frees.put("ba", freeIndex == 0 ? charged : BigDecimal.ZERO)
                    .put("bet", active.bet.size)
                    .put("l", active.bet.level)
                    .put("m", endingMultiplier)
                    .put("st", remaining)
                    .put("tt", freeTotal)
                    .put("twa", cumulative);
        }
        value.put("small_game_type", type == 2 ? 2 : 0);
        if (type == 2) value.put("forder_id", active.paidOid);
        value.put("roundKey", active.roundKey).put("deliveryIndex", deliveryIndex)
                .put("deliveryCount", active.deliveries.size());
        if (deliveryIndex == 0) active.paidOid = value.path("oid").asText();
        ObjectNode settled = value.deepCopy();
        settled.set("result", settled.path("props").deepCopy());
        active.settled.add(settled);
        active.nextIndex++;
        state.lastDelivery = value.deepCopy();
        if (active.nextIndex >= active.deliveries.size()) {
            state.history.add(0, historyRow(active));
            state.active = null;
        }
        return value;
    }

    private ObjectNode historyRow(ActiveRound active) {
        ObjectNode first = active.settled.get(0).deepCopy();
        first.remove("roundKey"); first.remove("deliveryIndex"); first.remove("deliveryCount");
        first.put("bet", active.bet.size);
        if (!first.has("time") || first.path("time").asLong() <= 0) {
            first.put("time", Instant.now().getEpochSecond());
        }
        ArrayNode results = first.putArray("results");
        for (ObjectNode step : active.settled) {
            ObjectNode copy = step.deepCopy();
            copy.remove("roundKey"); copy.remove("deliveryIndex"); copy.remove("deliveryCount");
            copy.put("bet", active.bet.size);
            results.add(copy);
        }
        return first;
    }

    private ObjectNode initResponse(SessionState state) throws Exception {
        synchronized (state) {
            ObjectNode data;
            if (state.active != null && state.lastDelivery != null) data = state.lastDelivery.deepCopy();
            else data = initialBoard(state.balance);
            data.set("prop_odds", payTable());
            ObjectNode response = ok(); response.set("data", data); return response;
        }
    }

    private ObjectNode initialBoard(BigDecimal balance) {
        ObjectNode data = JSON.createObjectNode();
        data.put("bet", 0.02).put("bet_gold", 0).put("change_gold", 0).put("end_gold", balance)
                .put("frees", false).put("level", 10).put("odds", 0).put("oid", "INIT-2010")
                .put("small_game_type", 0).put("start_gold", balance).put("total_win", 0).put("type", 1);
        ArrayNode props = data.putArray("props");
        ObjectNode page = props.addObject();
        page.set("gf", JSON.createArrayNode());
        page.set("grids", JSON.valueToTree(List.of(
                List.of(5, 6, 7, 8), List.of(11, 12), List.of(17, 18), List.of(20, 21), List.of(22, 23, 24))));
        page.put("m", "1");
        page.set("prop", JSON.valueToTree(List.of(
                5, 8, 5, 10, 2, 4, 4, 4, 4, 2, 7, 9, 9, 9, 5, 4, 7, 10, 10, 6, 10, 10, 3, 3, 3, 6, 7, 5, 6, 6)));
        page.set("sl", JSON.valueToTree(List.of(
                List.of(11, 12), List.of(17, 18), List.of(20, 21), List.of(22, 23, 24))));
        page.set("trl", JSON.valueToTree(List.of(3, 4, 3, 6)));
        page.put("tw", 0);
        page.set("win_arr", JSON.createArrayNode());
        return data;
    }

    private ObjectNode configResponse(HttpExchange exchange, Map<String, String> form) {
        HostPort hp = hostPort(exchange);
        String language = form.getOrDefault("language", "pt-br");
        ObjectNode data = JSON.createObjectNode();
        data.put("language", language).put("zone", 0).put("r", 1);
        data.putObject("initial_config").put("is_stopgs", 0).put("is_debug", false)
                .put("current_sys_time", Instant.now().getEpochSecond()).put("bd_bet_count", 2).put("user_on_hook_time", 600);
        ObjectNode game = data.putObject("game_info");
        game.put("gid", 2010).put("name", "EDM Mania").put("default_bet_gold", 0)
                .put("default_level", 10).put("least_gold", 0).put("buy_free_max_bet", 0).put("status", "1");
        game.set("bet_gold", JSON.valueToTree(List.of(0.02, 0.1, 0.2)));
        ObjectNode way = game.putArray("game_way").addObject();
        way.put("way_id", 201010000).put("min_bet_gold", "0.00").put("max_bet_gold", "0.00").put("win_multi", "1.00");
        ObjectNode gs = data.putObject("game_server");
        gs.put("ngs_switch", 0).put("gs_host1", hp.host).put("gs_port1", hp.port).put("gs_sport1", 0)
                .put("gs_push_host", hp.host).put("gs_push_port", hp.port).put("gs_push_sport", 0)
                .put("gos_host", hp.host).put("gos_port", hp.port).put("gos_sport", 0)
                .put("ps_host", hp.host).put("ps_port", hp.port);
        data.putObject("game_address").putObject("ship_address_config");
        ObjectNode response = ok(); response.set("data", data); return response;
    }

    private ObjectNode userResponse(HttpExchange exchange, SessionState state) {
        ObjectNode data = JSON.createObjectNode();
        data.put("token", state.key).put("uid", 2010001).put("nickname", "EDM Mania Demo")
                .put("gold", state.balance).put("currency_symbol", "R$").put("gid", 2010)
                .put("day_first_login", 0).put("is_guide", 0).put("total_recharge", "0");
        data.putArray("user_config");
        ObjectNode response = ok(); response.set("data", data); return response;
    }

    private ObjectNode activityResponse() {
        ObjectNode data = JSON.createObjectNode();
        ObjectNode free = data.putObject("free"); free.putArray("act_list"); free.put("invite_act_have", 0).put("invite_end_time", 0);
        ObjectNode response = ok(); response.set("data", data); return response;
    }

    private ObjectNode historySummary(SessionState state) {
        synchronized (state) {
            BigDecimal bet = BigDecimal.ZERO, change = BigDecimal.ZERO;
            for (ObjectNode n : state.history) {
                bet = bet.add(n.path("bet_gold").decimalValue());
                change = change.add(n.path("change_gold").decimalValue());
            }
            ObjectNode data = historyData(bet, change);
            if (!state.history.isEmpty()) data.withArray("list").addObject()
                    .put("day", Instant.now().getEpochSecond() / 86400 * 86400).put("bet_gold", bet).put("change_gold", change);
            ObjectNode response = ok(); response.set("data", data); return response;
        }
    }

    private ObjectNode historyDetail(SessionState state, Map<String, String> form) {
        int page = positiveInt(form.getOrDefault("page", "1"), "page");
        int pageSize = positiveInt(form.getOrDefault("page_size", "30"), "page_size");
        String dayRaw = form.get("day");
        synchronized (state) {
            List<ObjectNode> ordered = state.history.stream()
                    .sorted(Comparator.comparingLong(n -> -n.path("time").asLong())).toList();
            if (dayRaw != null && !dayRaw.isBlank()) {
                long day = Long.parseLong(dayRaw);
                ordered = ordered.stream().filter(n -> {
                    long t = n.path("time").asLong();
                    return t / 86400 * 86400 == day;
                }).toList();
            }
            BigDecimal bet = BigDecimal.ZERO, change = BigDecimal.ZERO;
            for (ObjectNode n : ordered) {
                bet = bet.add(n.path("bet_gold").decimalValue());
                change = change.add(n.path("change_gold").decimalValue());
            }
            ObjectNode data = historyData(bet, change);
            ArrayNode list = data.withArray("list");
            int from = Math.min(ordered.size(), (page - 1) * pageSize), to = Math.min(ordered.size(), from + pageSize);
            for (ObjectNode spin : ordered.subList(from, to)) {
                ObjectNode row = list.addObject();
                row.put("order_id", spin.path("order_id").asText()).put("time", spin.path("time").asLong())
                        .put("bet", spin.path("bet").isMissingNode() ? spin.path("bet_gold").decimalValue() : spin.path("bet").decimalValue())
                        .put("bet_gold", spin.path("bet_gold").decimalValue())
                        .put("change_gold", spin.path("change_gold").decimalValue()).put("type", spin.path("type").asInt())
                        .put("end_gold", spin.path("end_gold").decimalValue()).put("level", spin.path("level").asInt())
                        .put("oid", spin.path("oid").asText())
                        .put("start_gold", spin.path("start_gold").decimalValue())
                        .put("total_win", spin.path("total_win").decimalValue())
                        .put("small_game_type", spin.path("small_game_type").asInt())
                        .put("odds", spin.path("odds").decimalValue());
                row.set("extend", spin.path("extend").deepCopy());
                row.set("props", spin.path("props").deepCopy());
                row.set("frees", spin.path("frees").deepCopy());
                row.set("result", spin.path("result").deepCopy());
                row.set("results", spin.path("results").deepCopy());
            }
            ObjectNode response = ok(); response.set("data", data); return response;
        }
    }

    private ObjectNode historyData(BigDecimal bet, BigDecimal change) {
        ObjectNode data = JSON.createObjectNode(); data.putArray("list");
        data.putObject("statistics").put("total_bet_gold", bet).put("total_change_gold", change); return data;
    }

    private ArrayNode payTable() {
        ArrayNode table = JSON.createArrayNode();
        com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaResultUtil.copyPayTable()
                .entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ObjectNode symbol = table.addObject(); symbol.put("prop_id", entry.getKey()); ArrayNode odds = symbol.putArray("odds");
            int[] values = entry.getValue(); for (int i = 0; i < values.length; i++) odds.addObject().put("num", i + 3).put("odds", values[i]);
        }); return table;
    }

    private ObjectNode balanceData(SessionState state) { return JSON.createObjectNode().put("balance", state.balance); }
    private ObjectNode sessionData(SessionState state) {
        ObjectNode data = JSON.createObjectNode().put("session", state.key).put("balance", state.balance)
                .put("historyCount", state.history.size()).put("rulesHash", EdmManiaRulesMetadata.HASH);
        if (state.active != null) data.put("roundKey", state.active.roundKey).put("nextDeliveryIndex", state.active.nextIndex)
                .put("deliveryCount", state.active.deliveries.size());
        return data;
    }

    private ParsedBet parseBet(Map<String, String> form, boolean featureBuy) {
        BigDecimal raw = decimal(form.getOrDefault("bet_gold", "0.02"), "bet_gold");
        int level = positiveInt(form.getOrDefault("level", "10"), "level");
        for (BigDecimal size : BET_SIZES) {
            BigDecimal unit = size.multiply(BigDecimal.valueOf(level));
            BigDecimal waysCharge = unit.multiply(BigDecimal.valueOf(WAYS)).setScale(2, RoundingMode.HALF_UP);
            if (size.compareTo(raw) == 0 || waysCharge.compareTo(raw) == 0) {
                BigDecimal charged = featureBuy
                        ? waysCharge.multiply(BigDecimal.valueOf(BUY_MULTIPLE)).setScale(2, RoundingMode.HALF_UP)
                        : waysCharge;
                return new ParsedBet(size, level, unit, charged, featureBuy);
            }
        }
        throw new IllegalArgumentException("unsupported bet_gold");
    }

    private SessionState session(HttpExchange exchange) { return session(exchange, query(exchange.getRequestURI().getRawQuery())); }
    private SessionState session(HttpExchange exchange, Map<String, String> values) {
        String key = firstNonBlank(values.get("token"), query(exchange.getRequestURI().getRawQuery()).get("token"), "local-replay");
        return SESSIONS.computeIfAbsent(key, k -> new SessionState(k, initialBalance));
    }

    private void serveStatic(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        if ("/".equals(uri.getPath()) && needsHostRewrite(uri.getRawQuery(), exchange)) {
            String location = "/?" + rewrittenLaunchQuery(uri.getRawQuery(), exchange);
            exchange.getResponseHeaders().set("Location", location); sendEmpty(exchange, 302); return;
        }
        String rawPath = uri.getPath(); if ("/".equals(rawPath) || rawPath.isEmpty()) rawPath = "/index.html";
        Path file = publishRoot.resolve(rawPath.substring(1)).normalize();
        if (!file.startsWith(publishRoot) || !Files.isRegularFile(file)) { sendError(exchange, 404, "static file not found"); return; }
        byte[] body = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", mime(file));
        send(exchange, 200, body);
    }

    private boolean needsHostRewrite(String rawQuery, HttpExchange exchange) {
        Map<String, String> q = query(rawQuery); String expected = hostPort(exchange).authority();
        return !expected.equalsIgnoreCase(q.getOrDefault("sip", "")) || !q.containsKey("token") || !q.containsKey("gid");
    }

    private String rewrittenLaunchQuery(String rawQuery, HttpExchange exchange) {
        Map<String, String> q = new LinkedHashMap<>(query(rawQuery));
        q.putIfAbsent("ai", "luck_single_10229"); q.putIfAbsent("btt", "1"); q.put("gid", "2010");
        q.putIfAbsent("l", "pt"); q.putIfAbsent("language", "pt-br"); q.put("sip", hostPort(exchange).authority());
        q.putIfAbsent("t", "local-replay"); q.putIfAbsent("token", "local-replay");
        return q.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).reduce((a, b) -> a + "&" + b).orElse("");
    }

    private static Map<String, String> form(HttpExchange exchange) throws IOException {
        return query(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }
    private static Map<String, String> query(String raw) {
        Map<String, String> result = new LinkedHashMap<>(); if (raw == null || raw.isBlank()) return result;
        for (String pair : raw.split("&")) { String[] parts = pair.split("=", 2); result.put(decode(parts[0]), parts.length > 1 ? decode(parts[1]) : ""); }
        return result;
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String decode(String value) { return URLDecoder.decode(value, StandardCharsets.UTF_8); }

    private static ObjectNode ok() {
        return JSON.createObjectNode().put("code", 0).put("msg", "success")
                .put("time", Long.toString(Instant.now().getEpochSecond()));
    }
    private static void sendJson(HttpExchange exchange, JsonNode value) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        send(exchange, 200, JSON.writeValueAsBytes(value));
    }
    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        ObjectNode body = JSON.createObjectNode().put("code", status).put("msg", message);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        send(exchange, status, JSON.writeValueAsBytes(body));
    }
    private static void sendEmpty(HttpExchange exchange, int status) throws IOException { send(exchange, status, new byte[0]); }
    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        Headers h = exchange.getResponseHeaders(); h.set("Cache-Control", "no-store"); h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS"); h.set("Access-Control-Allow-Headers", "Content-Type, Idempotency-Key");
        h.set("Access-Control-Allow-Private-Network", "true");
        exchange.sendResponseHeaders(status, body.length); if (body.length > 0) exchange.getResponseBody().write(body);
    }

    private static String mime(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) return "text/html; charset=utf-8"; if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8"; if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg"; if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".ttf")) return "font/ttf"; if (name.endsWith(".plist")) return "application/xml";
        return "application/octet-stream";
    }

    private static HostPort hostPort(HttpExchange exchange) {
        String authority = exchange.getRequestHeaders().getFirst("Host");
        if (authority == null || authority.isBlank()) authority = exchange.getLocalAddress().getHostString() + ":" + exchange.getLocalAddress().getPort();
        String host = authority; int port = exchange.getLocalAddress().getPort();
        int colon = authority.lastIndexOf(':');
        if (colon > 0 && authority.indexOf(':') == colon) { host = authority.substring(0, colon); try { port = Integer.parseInt(authority.substring(colon + 1)); } catch (NumberFormatException ignored) { } }
        return new HostPort(host, port);
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) throw new IllegalArgumentException("options must be --name value");
            result.put(args[i].substring(2), args[i + 1]);
        } return result;
    }
    private static int requiredPort(Map<String, String> options) {
        String raw = firstNonBlank(options.get("port"), System.getProperty("cpgame.demo.port"), System.getenv("CPGAME_DEMO_PORT"));
        if (raw == null) throw new IllegalArgumentException("platform must inject --port in range 50000-59999");
        int port = Integer.parseInt(raw); if (port < 50000 || port > 59999) throw new IllegalArgumentException("port must be 50000-59999"); return port;
    }
    private static Properties loadConfig(String value) throws IOException {
        Properties result = new Properties(); if (value == null || value.isBlank()) return result;
        Path path = Path.of(value).toAbsolutePath().normalize(); if (!Files.isRegularFile(path)) throw new IllegalArgumentException("config not found: " + path);
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { result.load(reader); } return result;
    }
    private static Path resolvePublish(String value) throws Exception {
        if (value != null && !value.isBlank()) { Path direct = Path.of(value).toAbsolutePath().normalize(); if (Files.isRegularFile(direct.resolve("index.html"))) return direct; }
        Path jar = Path.of(EdmManiaController.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
        Path root = Files.isRegularFile(jar) ? jar.getParent().getParent().getParent().getParent() : Path.of("D:/work/hd/cpgame");
        Path derived = root.resolve("publish/2010-EDM-Mania").normalize();
        if (!Files.isRegularFile(derived.resolve("index.html"))) throw new IllegalArgumentException("publish/index.html not found: " + derived);
        return derived;
    }
    private static BigDecimal decimal(String value, String name) { BigDecimal result = new BigDecimal(value); if (result.signum() <= 0) throw new IllegalArgumentException(name + " must be positive"); return result; }
    private static int positiveInt(String value, String name) { int result = Integer.parseInt(value); if (result <= 0) throw new IllegalArgumentException(name + " must be positive"); return result; }
    private static String firstNonBlank(String... values) { for (String value : values) if (value != null && !value.isBlank()) return value; return null; }

    private record HostPort(String host, int port) { String authority() { return host + ":" + port; } }
    private record ParsedBet(BigDecimal size, int level, BigDecimal unitBet, BigDecimal charged, boolean featureBuy) { }
    private static final class ActiveRound {
        final String roundKey; final List<ObjectNode> deliveries; int nextIndex; final ParsedBet bet;
        final List<ObjectNode> settled = new ArrayList<>();
        String paidOid = "";
        ActiveRound(String roundKey, List<ObjectNode> deliveries, int nextIndex, ParsedBet bet) {
            this.roundKey = roundKey; this.deliveries = deliveries; this.nextIndex = nextIndex; this.bet = bet;
        }
    }
    private static final class SessionState {
        final String key; BigDecimal balance; ActiveRound active; ObjectNode lastDelivery;
        final List<ObjectNode> history = new ArrayList<>(); final Map<String, ObjectNode> idempotent = new LinkedHashMap<>();
        SessionState(String key, BigDecimal balance) { this.key = key; this.balance = balance.setScale(2, RoundingMode.HALF_UP); }
    }

    static final class RedisPool implements AutoCloseable {
        private final Properties config;
        private final long gameId;
        private final Object lock = new Object();
        private volatile RedisDirectLoader.RedisConnection redis;

        private RedisPool(Properties config, long gameId) { this.config = config; this.gameId = gameId; }
        static RedisPool create(Properties config) {
            return new RedisPool(config, Long.parseLong(config.getProperty("redis.game-id", "8002010")));
        }

        void ensure() throws IOException {
            if (redis != null) return;
            synchronized (lock) {
                if (redis != null) return;
                int port = Integer.parseInt(config.getProperty("redis.port", "8021"));
                int database = Integer.parseInt(config.getProperty("redis.database", "0"));
                String host = config.getProperty("redis.host", "18.234.101.161");
                if (!host.equals("18.234.101.161") || port != 8021 || database < 0) {
                    throw new IllegalStateException("EDM Mania Demo requires Redis 18.234.101.161:8021 db=15");
                }
                redis = RedisDirectLoader.RedisConnection.connect(host, port,
                        config.getProperty("redis.username", ""),
                        config.getProperty("redis.password", ""),
                        database, Boolean.parseBoolean(config.getProperty("redis.ssl", "false")),
                        Integer.parseInt(config.getProperty("redis.connect-timeout-ms", "5000")),
                        Integer.parseInt(config.getProperty("redis.socket-timeout-ms", "30000")));
            }
        }

        DrawnRound take(SecureRandom random, double lossProbability) throws IOException {
            ensure();
            boolean wantLoss = random.nextDouble() < lossProbability;
            DrawnRound drawn = wantLoss ? takeLoss(random) : takeWin(random);
            if (drawn == null) throw new IllegalStateException("PREGENERATED_CACHE_EMPTY");
            return drawn;
        }

        DrawnRound takeSpecial(SecureRandom random) throws IOException {
            ensure();
            List<Integer> available = new ArrayList<>();
            for (int ratio : ratios(true)) {
                if (ratio > 0 && llen(true, ratio) > 0) available.add(ratio);
            }
            if (available.isEmpty()) throw new IllegalStateException("PREGENERATED_CACHE_EMPTY");
            int ratio = available.get(random.nextInt(available.size()));
            DrawnRound drawn = readMember(true, ratio, random);
            if (drawn == null) throw new IllegalStateException("PREGENERATED_CACHE_EMPTY");
            return drawn;
        }

        private DrawnRound takeLoss(SecureRandom random) throws IOException {
            return readMember(false, 0, random);
        }

        private DrawnRound takeWin(SecureRandom random) throws IOException {
            List<int[]> candidates = new ArrayList<>();
            for (boolean special : new boolean[]{false, true}) {
                for (int ratio : ratios(special)) {
                    if (ratio <= 0) continue;
                    if (llen(special, ratio) > 0) candidates.add(new int[]{special ? 1 : 0, ratio});
                }
            }
            if (candidates.isEmpty()) return null;
            int[] chosen = candidates.get(random.nextInt(candidates.size()));
            return readMember(chosen[0] == 1, chosen[1], random);
        }

        private List<Integer> ratios(boolean special) throws IOException {
            String index = special ? RedisPackCli.maryIndex(gameId) : RedisPackCli.normalIndex(gameId);
            Object raw = redis.command("ZRANGE", index, "0", "-1");
            List<Integer> out = new ArrayList<>();
            if (raw instanceof List<?> list) {
                for (Object item : list) out.add(Integer.parseInt(item.toString()));
            }
            return out;
        }

        private long llen(boolean special, int ratio) throws IOException {
            Object len = redis.command("LLEN", special ? RedisPackCli.maryList(gameId, ratio) : RedisPackCli.normalList(gameId, ratio));
            return len instanceof Long value ? value : Long.parseLong(len.toString());
        }

        private DrawnRound readMember(boolean special, int ratio, SecureRandom random) throws IOException {
            long len = llen(special, ratio);
            if (len <= 0) return null;
            int offset = random.nextInt((int) Math.min(len, Integer.MAX_VALUE));
            Object member = redis.command("LINDEX",
                    special ? RedisPackCli.maryList(gameId, ratio) : RedisPackCli.normalList(gameId, ratio),
                    Integer.toString(offset));
            if (member == null) return null;
            return new DrawnRound(new CompleteRoundCodec().decode(member.toString()), ratio);
        }

        @Override public void close() throws IOException {
            RedisDirectLoader.RedisConnection connection = redis;
            if (connection != null) connection.close();
        }

        record DrawnRound(CompleteRoundFact fact, int ratio) { }
    }
}
