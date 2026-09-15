package com.cpgame.replica.hotpot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotRoundKind;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotSpinMode;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Controller 交付合同 v3：唯一受管 PID、平台注入 5xxxx 端口。
 * 只把 generator 里那一份 GameRuleCore 接到协议；Demo 结果只从 18.234.101.161:8021 db=15 领取完整局。
 */
public final class HotpotController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, SessionState> SESSIONS = new ConcurrentHashMap<>();
    private static final int GID = 1830;
    private static final String DEMO_TOKEN = "local-replay";
    private static final String SESSION_COOKIE = "cpgame1830sid";
    private static final AtomicLong OID = new AtomicLong(System.currentTimeMillis() * 1000L);

    private final Path publishRoot;
    private final BigDecimal initialBalance;
    private final RedisRoundStore redis;
    private final HotpotSpinProjector projector;
    private final SecureRandom random;

    HotpotController(Path publishRoot, Properties config, RedisRoundStore redis) {
        this(publishRoot, config, redis, RANDOM);
    }

    HotpotController(Path publishRoot, Properties config, RedisRoundStore redis, SecureRandom random) {
        this.publishRoot = publishRoot.toAbsolutePath().normalize();
        this.initialBalance = new BigDecimal(config.getProperty("session.initial-balance", "10000.00"))
                .setScale(2, RoundingMode.HALF_UP);
        this.redis = redis;
        this.projector = new HotpotSpinProjector();
        this.random = random;
        if (projector.core().rawGameId() != GID) throw new IllegalStateException("gid must stay 1830");
        if (!projector.core().implementationAllowed()) throw new IllegalStateException("GameRuleCore refused");
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        int port = requiredPort(options);
        Properties config = loadConfig(options.get("config"));
        rejectSeed(config);
        Path publish = resolvePublish(options.getOrDefault("publish", config.getProperty("publish.directory", "")));
        RedisRoundStore redis = RedisRoundStore.connect(config);
        HotpotController controller = new HotpotController(publish, config, redis);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", controller::handle);
        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            try { redis.close(); } catch (IOException ignored) { }
        }, "hotpot-controller-stop"));
        server.start();
        System.out.printf("CONTROLLER_READY gameId=1830 port=%d pid=%d rulesVersion=%s rulesHash=%s engineHash=%s publish=%s redis=%s:%s db=%s%n",
                port, ProcessHandle.current().pid(), HotpotRulesMetadata.VERSION, HotpotRulesMetadata.PROTOCOL_HASH,
                HotpotRulesMetadata.HASH, publish, config.getProperty("redis.host", "18.234.101.161"),
                config.getProperty("redis.port", "8021"), config.getProperty("redis.database", "0"));
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendEmpty(exchange, 204);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod()) || "HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                if ("/api/balance".equals(path)) {
                    ObjectNode body = ok();
                    body.set("data", balanceData(session(exchange)));
                    sendJson(exchange, body);
                    return;
                }
                if ("/api/session".equals(path)) {
                    ObjectNode body = ok();
                    body.set("data", sessionData(session(exchange)));
                    sendJson(exchange, body);
                    return;
                }
                serveStatic(exchange);
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "method not allowed");
                return;
            }
            Map<String, String> form = form(exchange);
            SessionState session = session(exchange, form);
            switch (path) {
                case "/cp/config/initialData" -> sendJson(exchange, configResponse(exchange, form));
                case "/cp/account/getUserInfo" -> sendJson(exchange, userResponse(exchange, session));
                case "/cp/activity/getActivity" -> sendJson(exchange, activityResponse());
                case "/cp/single_game.Game/initRoom" -> sendJson(exchange, initResponse(session));
                case "/cp/single_game.Game/gameResult" -> sendJson(exchange, spinResponse(exchange, session, form));
                case "/cp/goldgame/single_game_user_gold_history" -> sendJson(exchange, historySummary(session, form));
                case "/cp/goldgame/single_game_user_history" -> sendJson(exchange, historyDetail(session, form));
                case "/api/balance" -> {
                    ObjectNode body = ok();
                    body.set("data", balanceData(session));
                    sendJson(exchange, body);
                }
                case "/api/session" -> {
                    ObjectNode body = ok();
                    body.set("data", sessionData(session));
                    sendJson(exchange, body);
                }
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
            if ("3".equals(form.get("bet_type"))) {
                throw new IllegalArgumentException("this game has no feature buy");
            }
            String idempotencyKey = firstNonBlank(exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                    form.get("idempotency_key"), form.get("request_id"));
            if (idempotencyKey != null && state.idempotent.containsKey(idempotencyKey)) {
                return state.idempotent.get(idempotencyKey).deepCopy();
            }
            BigDecimal betSize = decimal(form.getOrDefault("bet_gold", "0.02"), "bet_gold");
            int level = positiveInt(form.getOrDefault("level", "1"), "level");
            if (level < 1 || level > 10) throw new IllegalArgumentException("level out of range 1..10");
            if (state.active != null && state.active.resumePending) {
                state.active.resumePending = false;
                ObjectNode response = ok();
                response.set("data", resumeActiveRound(state));
                if (idempotencyKey != null) state.idempotent.put(idempotencyKey, response.deepCopy());
                return response;
            }
            if (state.active == null) {
                BigDecimal charged = projector.chargedStake(betSize, level);
                if (state.balance.compareTo(charged) < 0) throw new IllegalArgumentException("insufficient balance");
                RedisRoundStore.ClaimedRound claimed = claimRound(state, form);
                state.active = new ActiveRound(UUID.randomUUID().toString(), claimed, betSize, level);
            }
            ObjectNode settled = settleNext(state);
            ObjectNode response = ok();
            response.set("data", settled);
            if (idempotencyKey != null) state.idempotent.put(idempotencyKey, response.deepCopy());
            return response;
        }
    }

    private ObjectNode settleNext(SessionState state) {
        ActiveRound active = state.active;
        int spinIndex = active.nextSpin;
        List<CompleteRoundFact.BoardFact> pages = active.claimed.fact().spins().get(spinIndex);
        HotpotSpinMode mode = spinIndex == 0 ? HotpotSpinMode.PAID : HotpotSpinMode.FREE;
        long oid = OID.incrementAndGet();
        HotpotSpinProjector.ProjectedSpin projected = projector.project(
                pages, mode, active.betSize, active.level, state.balance, oid,
                active.remaining, active.totalAwarded, active.featureWin);
        ObjectNode data = projected.data();
        HotpotClientRoundWalk.requirePlayable(data);
        data.put("order_id", oid + "-" + GID);
        data.put("roundKey", active.roundKey);
        data.put("kind", active.claimed.kind().name());
        data.put("deliveryIndex", spinIndex);
        data.put("deliveryCount", active.claimed.fact().spins().size());
        data.put("_source", "redis-db15-complete-round");
        state.balance = data.path("end_gold").decimalValue();
        active.remaining = projected.remaining();
        active.totalAwarded = projected.totalAwarded();
        active.featureWin = projected.featureWin();
        active.nextSpin++;
        state.lastDelivery = data.deepCopy();
        active.deliveries.add(data.deepCopy());
        if (active.nextSpin >= active.claimed.fact().spins().size()) {
            state.history.add(0, historyItem(active, data.deepCopy()));
            state.active = null;
        }
        return data;
    }

    private RedisRoundStore.ClaimedRound claimRound(SessionState state, Map<String, String> form) throws Exception {
        String requested = form.get("playthrough_kind");
        if (requested == null || requested.isBlank()) return redis.claim(random);
        if (!state.key.startsWith("playthrough-")) {
            throw new IllegalArgumentException("playthrough_kind is only allowed on platform playthrough tokens");
        }
        HotpotRoundKind kind;
        try {
            kind = HotpotRoundKind.valueOf(requested.trim());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("unknown playthrough_kind: " + requested);
        }
        return redis.claimKind(kind, random);
    }

    private ObjectNode historyItem(ActiveRound active, ObjectNode last) {
        ObjectNode item = last.deepCopy();
        item.remove("_source");
        ArrayNode results = JSON.createArrayNode();
        String parentOrder = null;
        BigDecimal roundChange = BigDecimal.ZERO;
        for (int i = 0; i < active.deliveries.size(); i++) {
            ObjectNode spin = active.deliveries.get(i);
            ArrayNode pages = (ArrayNode) spin.path("props").deepCopy();
            boolean paid = i == 0;
            if (paid) parentOrder = spin.path("order_id").asText();
            ObjectNode group = projector.historyGroup(spin, pages, paid, parentOrder);
            results.add(group);
            roundChange = roundChange.add(spin.path("change_gold").decimalValue());
        }
        item.set("result", active.deliveries.get(0).path("props").deepCopy());
        item.set("results", results);
        ObjectNode extend = item.putObject("extend");
        extend.put("act_bet_gold", 0).put("act_id", "0").put("act_type", 0);
        item.put("change_gold", HotpotSpinProjector.money(roundChange));
        item.put("roundKey", active.roundKey);
        item.put("kind", active.claimed.kind().name());
        item.put("day", Instant.now().getEpochSecond() / 86400L * 86400L);
        return item;
    }

    private ObjectNode initResponse(SessionState state) {
        synchronized (state) {
            ObjectNode data;
            if (state.active != null && state.active.nextSpin > 0) {
                state.active.resumePending = true;
                data = resumeInit(state);
            } else if (state.lastDelivery != null) {
                data = state.lastDelivery.deepCopy();
                data.set("prop_odds", projector.payTable());
            } else {
                data = projector.idleInit(state.balance, new BigDecimal("0.02"), 1);
            }
            ObjectNode response = ok();
            response.set("data", data);
            return response;
        }
    }

    /**
     * Init must leave the original page in its normal clickable state. Advertising frees.st here makes
     * RecGameDates enter ScatterModel before a BetResult transition exists, which can leave gameStatus
     * locked. The following paid-trigger replay is returned by spinResponse without charging or advancing.
     */
    private ObjectNode resumeInit(SessionState state) {
        ActiveRound active = state.active;
        ObjectNode data = projector.idleInit(state.balance, active.betSize, active.level);
        data.put("roundKey", active.roundKey);
        data.put("kind", active.claimed.kind().name());
        data.put("deliveryIndex", active.nextSpin - 1);
        data.put("deliveryCount", active.claimed.fact().spins().size());
        data.put("resumeNextDelivery", active.nextSpin);
        data.put("_resumeAvailable", true);
        return data;
    }

    /**
     * Free-spin mode is memory-only in the original client. The first Spin after resume replays the paid
     * trigger through RecBetResult so the untouched frontend rebuilds isScatterIng. The following request
     * still continues at active.nextSpin and never claims another Round.
     */
    private ObjectNode resumeActiveRound(SessionState state) {
        ActiveRound active = state.active;
        ObjectNode data = active.deliveries.get(0).deepCopy();
        ObjectNode frees = data.with("frees");
        frees.put("ba", projector.chargedStake(active.betSize, active.level));
        frees.put("bet", active.betSize.setScale(2, RoundingMode.HALF_UP));
        frees.put("l", active.level);
        frees.put("st", active.remaining);
        frees.put("tt", active.totalAwarded);
        frees.put("twa", active.featureWin.setScale(2, RoundingMode.HALF_UP));
        data.put("end_gold", state.balance.setScale(2, RoundingMode.HALF_UP));
        data.put("deliveryIndex", active.nextSpin - 1);
        data.put("deliveryCount", active.claimed.fact().spins().size());
        data.put("resumeNextDelivery", active.nextSpin);
        data.put("_resume", true);
        data.set("prop_odds", projector.payTable());
        HotpotClientRoundWalk.requirePlayable(data);
        return data;
    }

    private ObjectNode configResponse(HttpExchange exchange, Map<String, String> form) {
        HostPort hp = hostPort(exchange);
        String language = form.getOrDefault("language", "pt-br");
        ObjectNode data = JSON.createObjectNode();
        data.put("language", "pt-br".equalsIgnoreCase(language) ? "pt-pt" : language).put("zone", "UTC").put("r", 1);
        data.putObject("initial_config").put("is_stopgs", 0).put("is_debug", 0)
                .put("current_sys_time", Instant.now().getEpochSecond());
        ObjectNode game = data.putObject("game_info");
        game.put("gid", GID).put("game_name", "Hotpot").put("default_bet_gold", 0.02)
                .put("default_level", 1).put("least_gold", 0.02).put("buy_free_max_bet", 0);
        game.set("bet_gold", JSON.valueToTree(List.of(0.02, 0.04, 0.1, 0.2, 0.5, 1)));
        ObjectNode way = game.putArray("game_way").addObject();
        way.put("way_id", 183010000).put("min_bet_gold", 0.02).put("max_bet_gold", 1000);
        ObjectNode gs = data.putObject("game_server");
        gs.put("ngs_switch", 0).put("gs_host1", hp.host).put("gs_port1", hp.port).put("gs_sport1", 0)
                .put("gs_push_host", hp.host).put("gs_push_port", hp.port).put("gs_push_sport", 0)
                .put("gos_host", hp.host).put("gos_port", hp.port).put("gos_sport", 0)
                .put("ps_host", hp.host).put("ps_port", hp.port);
        ObjectNode response = ok();
        response.set("data", data);
        return response;
    }

    private ObjectNode userResponse(HttpExchange exchange, SessionState state) {
        HostPort hp = hostPort(exchange);
        ObjectNode data = JSON.createObjectNode();
        data.put("token", state.key).put("uid", 1830001).put("user_id", 1830001).put("nickname", "Hotpot Demo")
                .put("gold", state.balance).put("currency", "BRL").put("currency_symbol", "R$")
                .put("ip", hp.host).put("room_mode", 0);
        ObjectNode response = ok();
        response.set("data", data);
        return response;
    }

    private ObjectNode activityResponse() {
        ObjectNode data = JSON.createObjectNode();
        ObjectNode free = data.putObject("free");
        free.putArray("act_list");
        free.put("invite_act_have", 0).put("invite_end_time", 0);
        ObjectNode response = ok();
        response.set("data", data);
        return response;
    }

    private ObjectNode historySummary(SessionState state, Map<String, String> form) {
        int page = positiveInt(form.getOrDefault("page", "1"), "page");
        int pageSize = positiveInt(form.getOrDefault("page_size", "30"), "page_size");
        synchronized (state) {
            Map<Long, BigDecimal[]> byDay = new LinkedHashMap<>();
            BigDecimal totalBet = BigDecimal.ZERO;
            BigDecimal totalChange = BigDecimal.ZERO;
            for (ObjectNode round : state.history) {
                long day = round.path("day").asLong();
                BigDecimal bet = round.path("bet_gold").decimalValue();
                BigDecimal change = round.path("change_gold").decimalValue();
                BigDecimal[] row = byDay.computeIfAbsent(day, key -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                row[0] = row[0].add(bet);
                row[1] = row[1].add(change);
                totalBet = totalBet.add(bet);
                totalChange = totalChange.add(change);
            }
            List<Map.Entry<Long, BigDecimal[]>> days = new ArrayList<>(byDay.entrySet());
            days.sort(Comparator.comparingLong((Map.Entry<Long, BigDecimal[]> e) -> e.getKey()).reversed());
            ObjectNode data = JSON.createObjectNode();
            ArrayNode list = data.putArray("list");
            int from = Math.min(days.size(), (page - 1) * pageSize);
            int to = Math.min(days.size(), from + pageSize);
            for (Map.Entry<Long, BigDecimal[]> entry : days.subList(from, to)) {
                list.addObject().put("day", entry.getKey())
                        .put("bet_gold", HotpotSpinProjector.money(entry.getValue()[0]))
                        .put("change_gold", HotpotSpinProjector.money(entry.getValue()[1]));
            }
            data.putObject("statistics").put("total_bet_gold", HotpotSpinProjector.money(totalBet))
                    .put("total_change_gold", HotpotSpinProjector.money(totalChange));
            ObjectNode response = ok();
            response.set("data", data);
            return response;
        }
    }

    private ObjectNode historyDetail(SessionState state, Map<String, String> form) {
        int page = positiveInt(form.getOrDefault("page", "1"), "page");
        int pageSize = positiveInt(form.getOrDefault("page_size", "30"), "page_size");
        synchronized (state) {
            List<ObjectNode> ordered = new ArrayList<>(state.history);
            if (form.containsKey("day") && !form.get("day").isBlank()) {
                long day = Long.parseLong(form.get("day"));
                ordered = ordered.stream().filter(n -> n.path("day").asLong() == day).toList();
            }
            BigDecimal totalBet = ordered.stream().map(n -> n.path("bet_gold").decimalValue())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalChange = ordered.stream().map(n -> n.path("change_gold").decimalValue())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            ObjectNode data = JSON.createObjectNode();
            ArrayNode list = data.putArray("list");
            int from = Math.min(ordered.size(), (page - 1) * pageSize);
            int to = Math.min(ordered.size(), from + pageSize);
            for (ObjectNode round : ordered.subList(from, to)) list.add(round.deepCopy());
            data.putObject("statistics").put("total_bet_gold", HotpotSpinProjector.money(totalBet))
                    .put("total_change_gold", HotpotSpinProjector.money(totalChange));
            ObjectNode response = ok();
            response.set("data", data);
            return response;
        }
    }

    private ObjectNode balanceData(SessionState state) {
        return JSON.createObjectNode().put("balance", state.balance);
    }

    private ObjectNode sessionData(SessionState state) {
        ObjectNode data = JSON.createObjectNode().put("session", state.key).put("balance", state.balance)
                .put("historyCount", state.history.size()).put("rulesHash", HotpotRulesMetadata.PROTOCOL_HASH)
                .put("gid", GID);
        if (state.active != null) {
            data.put("roundKey", state.active.roundKey).put("nextSpinIndex", state.active.nextSpin)
                    .put("deliveryCount", state.active.claimed.fact().spins().size())
                    .put("kind", state.active.claimed.kind().name());
        }
        return data;
    }

    private SessionState session(HttpExchange exchange) {
        return session(exchange, query(exchange.getRequestURI().getRawQuery()));
    }

    private SessionState session(HttpExchange exchange, Map<String, String> values) {
        String key = firstNonBlank(values.get("token"), values.get("t"),
                query(exchange.getRequestURI().getRawQuery()).get("token"), DEMO_TOKEN);
        if (DEMO_TOKEN.equals(key)) {
            String browserSession = cookie(exchange, SESSION_COOKIE);
            if (browserSession != null) key = DEMO_TOKEN + "@" + browserSession;
        }
        return SESSIONS.computeIfAbsent(key, k -> new SessionState(k, initialBalance));
    }

    private static String cookie(HttpExchange exchange, String name) {
        List<String> headers = exchange.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String header : headers) {
            for (String part : header.split(";")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length == 2 && name.equals(pair[0]) && pair[1].matches("[A-Za-z0-9-]{8,80}")) {
                    return pair[1];
                }
            }
        }
        return null;
    }

    private void serveStatic(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        if ("/".equals(uri.getPath()) && needsHostRewrite(uri.getRawQuery(), exchange)) {
            String location = "/?" + rewrittenLaunchQuery(uri.getRawQuery(), exchange);
            exchange.getResponseHeaders().set("Location", location);
            sendEmpty(exchange, 302);
            return;
        }
        String rawPath = uri.getPath();
        if ("/".equals(rawPath) || rawPath.isEmpty()) rawPath = "/index.html";
        if ("/index.html".equals(rawPath) && cookie(exchange, SESSION_COOKIE) == null) {
            exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=" + UUID.randomUUID()
                    + "; Path=/; SameSite=Lax");
        }
        Path file = publishRoot.resolve(rawPath.substring(1)).normalize();
        if (!file.startsWith(publishRoot) || !Files.isRegularFile(file)) {
            sendError(exchange, 404, "static file not found");
            return;
        }
        byte[] body = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", mime(file));
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, body.length);
            return;
        }
        send(exchange, 200, body);
    }

    private boolean needsHostRewrite(String rawQuery, HttpExchange exchange) {
        Map<String, String> q = query(rawQuery);
        String expected = hostPort(exchange).authority();
        return !expected.equalsIgnoreCase(q.getOrDefault("sip", "")) || !q.containsKey("token") || !q.containsKey("gid");
    }

    private String rewrittenLaunchQuery(String rawQuery, HttpExchange exchange) {
        Map<String, String> q = new LinkedHashMap<>(query(rawQuery));
        q.putIfAbsent("ai", "luck_single_10229");
        q.putIfAbsent("btt", "1");
        q.put("gid", Integer.toString(GID));
        q.putIfAbsent("l", "pt");
        q.putIfAbsent("language", "pt-br");
        q.put("sip", hostPort(exchange).authority());
        q.putIfAbsent("t", "local-replay");
        q.putIfAbsent("token", "local-replay");
        return q.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .reduce((a, b) -> a + "&" + b).orElse("");
    }

    private static Map<String, String> form(HttpExchange exchange) throws IOException {
        String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> values = new LinkedHashMap<>();
        if (raw.startsWith("{")) {
            JsonNode node = JSON.readTree(raw);
            node.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
        } else {
            values.putAll(query(raw));
        }
        values.putAll(query(exchange.getRequestURI().getRawQuery()));
        return values;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return result;
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(decode(parts[0]), parts.length > 1 ? decode(parts[1]) : "");
        }
        return result;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value.replace("+", "%20"), StandardCharsets.UTF_8);
    }

    private static ObjectNode ok() {
        return JSON.createObjectNode().put("code", 0).put("msg", "success")
                .put("time", Long.toString(Instant.now().getEpochSecond()));
    }

    private static void sendJson(HttpExchange exchange, JsonNode value) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        send(exchange, 200, JSON.writeValueAsBytes(value));
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        ObjectNode body = JSON.createObjectNode().put("code", status).put("msg", message == null ? "" : message);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        send(exchange, status, JSON.writeValueAsBytes(body));
    }

    private static void sendEmpty(HttpExchange exchange, int status) throws IOException {
        send(exchange, status, new byte[0]);
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        Headers h = exchange.getResponseHeaders();
        h.set("Cache-Control", "no-store");
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type, Idempotency-Key");
        exchange.sendResponseHeaders(status, body.length);
        if (body.length > 0) exchange.getResponseBody().write(body);
    }

    private static String mime(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".ogg")) return "audio/ogg";
        if (name.endsWith(".ttf")) return "font/ttf";
        if (name.endsWith(".woff") || name.endsWith(".woff2")) return "font/woff";
        if (name.endsWith(".wasm")) return "application/wasm";
        if (name.endsWith(".plist")) return "application/xml";
        return "application/octet-stream";
    }

    private static HostPort hostPort(HttpExchange exchange) {
        String authority = exchange.getRequestHeaders().getFirst("Host");
        if (authority == null || authority.isBlank()) {
            authority = exchange.getLocalAddress().getHostString() + ":" + exchange.getLocalAddress().getPort();
        }
        String host = authority;
        int port = exchange.getLocalAddress().getPort();
        int colon = authority.lastIndexOf(':');
        if (colon > 0 && authority.indexOf(':') == colon) {
            host = authority.substring(0, colon);
            try { port = Integer.parseInt(authority.substring(colon + 1)); } catch (NumberFormatException ignored) { }
        }
        return new HostPort(host, port);
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) continue;
            String key = args[i].substring(2);
            String value = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true";
            result.put(key, value);
        }
        return result;
    }

    private static int requiredPort(Map<String, String> options) {
        String raw = firstNonBlank(options.get("port"), System.getProperty("cpgame.demo.port"),
                System.getenv("CPGAME_DEMO_PORT"));
        if (raw == null) throw new IllegalArgumentException("platform must inject --port in range 50000-59999");
        int port = Integer.parseInt(raw);
        if (port < 50000 || port > 59999) throw new IllegalArgumentException("port must be 50000-59999");
        return port;
    }

    private static Properties loadConfig(String value) throws IOException {
        Properties result = new Properties();
        if (value == null || value.isBlank()) return result;
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("config not found: " + path);
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { result.load(reader); }
        return result;
    }

    private static void rejectSeed(Properties config) {
        for (String key : config.stringPropertyNames()) {
            if (key.toLowerCase(Locale.ROOT).contains("seed")) {
                throw new IllegalArgumentException("formal controller config must not contain " + key);
            }
        }
    }

    private static Path resolvePublish(String value) throws Exception {
        if (value != null && !value.isBlank()) {
            Path direct = Path.of(value).toAbsolutePath().normalize();
            if (Files.isRegularFile(direct.resolve("index.html"))) return direct;
        }
        Path jar = Path.of(HotpotController.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath();
        Path root = Files.isRegularFile(jar) ? jar.getParent().getParent().getParent().getParent()
                : Path.of("D:/work/hd/cpgame");
        Path derived = root.resolve("publish/1830-Hotpot").normalize();
        if (!Files.isRegularFile(derived.resolve("index.html"))) {
            throw new IllegalArgumentException("publish/index.html not found: " + derived);
        }
        return derived;
    }

    private static BigDecimal decimal(String value, String name) {
        BigDecimal result = new BigDecimal(value);
        if (result.signum() <= 0) throw new IllegalArgumentException(name + " must be positive");
        return result;
    }

    private static int positiveInt(String value, String name) {
        int result = Integer.parseInt(value);
        if (result <= 0) throw new IllegalArgumentException(name + " must be positive");
        return result;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private record HostPort(String host, int port) {
        String authority() { return host + ":" + port; }
    }

    static final class ActiveRound {
        final String roundKey;
        final RedisRoundStore.ClaimedRound claimed;
        final BigDecimal betSize;
        final int level;
        final List<ObjectNode> deliveries = new ArrayList<>();
        int nextSpin;
        int remaining;
        int totalAwarded;
        BigDecimal featureWin = BigDecimal.ZERO;
        boolean resumePending;

        ActiveRound(String roundKey, RedisRoundStore.ClaimedRound claimed, BigDecimal betSize, int level) {
            this.roundKey = roundKey;
            this.claimed = claimed;
            this.betSize = betSize;
            this.level = level;
        }
    }

    static final class SessionState {
        final String key;
        BigDecimal balance;
        ActiveRound active;
        ObjectNode lastDelivery;
        final List<ObjectNode> history = new ArrayList<>();
        final Map<String, ObjectNode> idempotent = new LinkedHashMap<>();

        SessionState(String key, BigDecimal balance) {
            this.key = key;
            this.balance = balance.setScale(2, RoundingMode.HALF_UP);
        }
    }
}
