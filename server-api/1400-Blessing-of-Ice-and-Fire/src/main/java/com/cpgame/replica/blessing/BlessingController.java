package com.cpgame.replica.blessing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hd.pg.appapi.business.vo.cpgame.blessing.BlessingResultUtil;
import com.hd.pg.appapi.business.vo.cpgame.blessing.BlessingRoundFactory;
import com.hd.pg.appapi.business.vo.cpgame.blessing.BlessingRoundFactory.BlessingRound;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Controller v3: one managed PID, platform-injected 5xxxx port. Live multiplier map, no Redis cache. */
public final class BlessingController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, SessionState> SESSIONS = new ConcurrentHashMap<>();
    private static final int GID = 1400;
    private static final AtomicLong OID = new AtomicLong(System.currentTimeMillis() * 1000L);
    private static final List<BigDecimal> BET_SIZES = List.of(
            new BigDecimal("0.5"), new BigDecimal("5"), new BigDecimal("15"), new BigDecimal("50"));

    private final Path publishRoot;
    private final BigDecimal initialBalance;

    private BlessingController(Path publishRoot, Properties config) {
        this.publishRoot = publishRoot.toAbsolutePath().normalize();
        this.initialBalance = new BigDecimal(config.getProperty("session.initial-balance", "10000.00"))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        int port = requiredPort(options);
        Properties config = loadConfig(options.get("config"));
        Path publish = resolvePublish(options.getOrDefault("publish", config.getProperty("publish.directory", "")));
        BlessingController controller = new BlessingController(publish, config);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", controller::handle);
        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0), "blessing-controller-stop"));
        server.start();
        System.out.printf("CONTROLLER_READY gameId=1400 port=%d pid=%d rulesVersion=%s rulesHash=%s publish=%s generation=realtime%n",
                port, ProcessHandle.current().pid(), BlessingRulesMetadata.VERSION, BlessingRulesMetadata.HASH, publish);
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
                case "/cp/goldgame/single_game_user_gold_history" -> sendJson(exchange, historySummary(session));
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
        } catch (IllegalArgumentException ex) {
            sendError(exchange, 400, ex.getMessage());
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
            sendError(exchange, 500, "controller failure");
        } finally {
            exchange.close();
        }
    }

    private ObjectNode spinResponse(HttpExchange exchange, SessionState state, Map<String, String> form) {
        synchronized (state) {
            String idempotencyKey = firstNonBlank(exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                    form.get("idempotency_key"), form.get("request_id"));
            if (idempotencyKey != null && state.idempotent.containsKey(idempotencyKey)) {
                return state.idempotent.get(idempotencyKey).deepCopy();
            }
            int betType = Integer.parseInt(form.getOrDefault("bet_type", "3"));
            if (betType < 1 || betType > 3) throw new IllegalArgumentException("bet_type must be 1, 2 or 3");
            BigDecimal betSize = decimal(form.getOrDefault("bet_gold", "0.5"), "bet_gold");
            if (betSize.compareTo(new BigDecimal("0.5")) < 0) betSize = new BigDecimal("0.5");
            int level = positiveInt(form.getOrDefault("level", "1"), "level");
            if (level > 10) level = 10;
            String rawOdd = firstNonBlank(form.get("odd"), form.get("odds"));
            int requestedOdd = rawOdd == null
                    ? BlessingRoundFactory.sampleRequestedOdd(RANDOM, betType)
                    : Integer.parseInt(rawOdd);
            BlessingRound round = BlessingRoundFactory.generate(RANDOM, betType, betSize, level, requestedOdd);
            if (round.totalWin().signum() > 0) {
                BlessingResultUtil.BlessingPage top = BlessingResultUtil.evaluatePage(round.top().p());
                BlessingResultUtil.BlessingPage bottom = BlessingResultUtil.evaluatePage(round.bottom().p());
                if (betType != 2 && top.odd() != round.top().odd()) {
                    throw new IllegalStateException("top odd failed ResultUtil");
                }
                if (betType != 1 && bottom.odd() != round.bottom().odd()) {
                    throw new IllegalStateException("bottom odd failed ResultUtil");
                }
            }
            BigDecimal charged = round.charged();
            if (state.balance.compareTo(charged) < 0) throw new IllegalArgumentException("insufficient balance");
            long oid = OID.incrementAndGet();
            BigDecimal start = state.balance;
            state.balance = start.subtract(charged).add(round.totalWin()).setScale(2, RoundingMode.HALF_UP);
            ObjectNode data = spinData(round, oid, start, state.balance);
            state.lastDelivery = data.deepCopy();
            state.history.add(0, data.deepCopy());
            ObjectNode response = ok();
            response.set("data", data);
            if (idempotencyKey != null) state.idempotent.put(idempotencyKey, response.deepCopy());
            return response;
        }
    }

    private ObjectNode spinData(BlessingRound round, long oid, BigDecimal start, BigDecimal end) {
        long createdAt = Instant.now().getEpochSecond();
        ObjectNode data = JSON.createObjectNode();
        data.put("bet", round.betSize());
        data.put("bet_gold", round.charged());
        data.put("bet_type", round.betType());
        data.put("bet_size", round.betSize());
        data.put("bet_level", round.level());
        data.put("change_gold", round.totalWin().subtract(round.charged()));
        data.put("end_gold", end);
        data.put("balance_after", end.toPlainString());
        data.put("level", round.level());
        data.put("odds", round.odds());
        data.put("oid", Long.toString(oid));
        data.put("order_id", oid + "-" + GID);
        data.put("start_gold", start);
        data.put("total_win", round.totalWin());
        data.put("created_at", createdAt);
        data.put("time", createdAt);
        data.put("cc", "BRL");
        data.put("cs", "R$");
        ObjectNode extend = data.putObject("extend");
        extend.put("act_bet_gold", 0);
        extend.put("act_id", "0");
        extend.put("act_type", 0);
        data.put("roundKey", UUID.randomUUID().toString());
        data.put("_source", "realtime-java-rule-core");
        ObjectNode props = data.putObject("props");
        ObjectNode pr = props.putObject("pr");
        pr.set("1", pageNode(round.top(), round.topTw(), round.bothMultiplier(), round.betType() != 2));
        pr.set("2", pageNode(round.bottom(), round.bottomTw(), round.bothMultiplier(), round.betType() != 1));
        props.put("tw", round.totalWin());
        return data;
    }

    private ObjectNode pageNode(BlessingResultUtil.BlessingPage page, BigDecimal tw, int m, boolean active) {
        ObjectNode node = JSON.createObjectNode();
        node.put("m", active && tw.signum() > 0 ? m : 1);
        node.put("odd", active ? page.odd() : 0);
        ArrayNode p = node.putArray("p");
        for (int v : page.p()) p.add(v);
        node.put("tw", active ? tw : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        ArrayNode w = node.putArray("w");
        for (int v : page.w()) w.add(v);
        return node;
    }

    private ObjectNode initResponse(SessionState state) {
        synchronized (state) {
            ObjectNode data;
            if (state.lastDelivery != null) {
                data = state.lastDelivery.deepCopy();
            } else {
                BlessingRound idle = BlessingRoundFactory.generate(RANDOM, 3, new BigDecimal("0.5"), 1, 0);
                data = spinData(idle, 0, state.balance, state.balance);
                data.put("bet", 0);
                data.put("bet_gold", 0);
                data.put("change_gold", 0);
                data.put("total_win", 0);
                data.put("odds", 0);
                data.put("oid", "INIT-1400");
                data.put("order_id", "INIT-1400");
            }
            ObjectNode odds = data.putObject("prop_odds");
            BlessingResultUtil.payTable().forEach(odds::put);
            ObjectNode response = ok();
            response.set("data", data);
            return response;
        }
    }

    private ObjectNode configResponse(HttpExchange exchange, Map<String, String> form) {
        HostPort hp = hostPort(exchange);
        String language = form.getOrDefault("language", "pt-br");
        ObjectNode data = JSON.createObjectNode();
        data.put("language", "pt-br".equalsIgnoreCase(language) ? "pt-pt" : language).put("zone", "UTC").put("r", 1);
        data.putObject("initial_config").put("is_stopgs", 0).put("is_debug", 0)
                .put("current_sys_time", Instant.now().getEpochSecond()).put("bd_bet_count", 2);
        ObjectNode game = data.putObject("game_info");
        game.put("gid", GID).put("name", "Blessing of Ice and Fire").put("game_name", "Blessing of Ice and Fire")
                .put("default_bet_gold", 0.5).put("default_level", 10).put("least_gold", 0.5)
                .put("buy_free_max_bet", -1).put("status", "1");
        ArrayNode sizes = game.putArray("bet_gold");
        BET_SIZES.forEach(sizes::add);
        ObjectNode way = game.putArray("game_way").addObject();
        way.put("way_id", 140010000).put("min_bet_gold", 0.5).put("max_bet_gold", 1000).put("win_multi", "1.00");
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
        data.put("token", state.key).put("uid", 1400001).put("user_id", 1400001).put("nickname", "Blessing Demo")
                .put("gold", state.balance).put("currency", "BRL").put("currency_symbol", "R$")
                .put("ip", hp.host).put("room_mode", 0).put("gid", GID);
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

    private ObjectNode historySummary(SessionState state) {
        synchronized (state) {
            BigDecimal bet = BigDecimal.ZERO;
            BigDecimal change = BigDecimal.ZERO;
            for (ObjectNode n : state.history) {
                bet = bet.add(n.path("bet_gold").decimalValue());
                change = change.add(n.path("change_gold").decimalValue());
            }
            ObjectNode data = JSON.createObjectNode();
            ArrayNode list = data.putArray("list");
            if (!state.history.isEmpty()) {
                list.addObject().put("day", Instant.now().getEpochSecond() / 86400L * 86400L)
                        .put("bet_gold", bet).put("change_gold", change);
            }
            data.putObject("statistics").put("total_bet_gold", bet).put("total_change_gold", change);
            ObjectNode response = ok();
            response.set("data", data);
            return response;
        }
    }

    private ObjectNode historyDetail(SessionState state, Map<String, String> form) {
        int page = positiveInt(form.getOrDefault("page", "1"), "page");
        int pageSize = positiveInt(form.getOrDefault("page_size", "30"), "page_size");
        synchronized (state) {
            ObjectNode data = JSON.createObjectNode();
            ArrayNode list = data.putArray("list");
            int from = Math.min(state.history.size(), (page - 1) * pageSize);
            int to = Math.min(state.history.size(), from + pageSize);
            BigDecimal bet = BigDecimal.ZERO;
            BigDecimal change = BigDecimal.ZERO;
            for (ObjectNode spin : state.history) {
                bet = bet.add(spin.path("bet_gold").decimalValue());
                change = change.add(spin.path("change_gold").decimalValue());
            }
            for (ObjectNode spin : state.history.subList(from, to)) {
                ObjectNode row = historyRow(spin);
                ArrayNode results = row.putArray("results");
                results.add(historyRow(spin));
                list.add(row);
            }
            data.putObject("statistics").put("total_bet_gold", bet).put("total_change_gold", change);
            ObjectNode response = ok();
            response.set("data", data);
            return response;
        }
    }

    /** Fields Game1400DayHistoryItem / Game1400GameDetailView read: time, extend.act_id, props.pr, end_gold. */
    private ObjectNode historyRow(ObjectNode spin) {
        ObjectNode row = spin.deepCopy();
        long createdAt = spin.path("created_at").asLong(spin.path("time").asLong(Instant.now().getEpochSecond()));
        if (!row.has("created_at")) row.put("created_at", createdAt);
        if (!row.has("time")) row.put("time", createdAt);
        if (!row.has("extend")) {
            ObjectNode extend = row.putObject("extend");
            extend.put("act_bet_gold", 0);
            extend.put("act_id", "0");
            extend.put("act_type", 0);
        }
        if (!row.has("balance_after")) row.put("balance_after", row.path("end_gold").asText("0"));
        if (!row.has("bet_size")) row.put("bet_size", row.path("bet").asDouble());
        if (!row.has("bet_level")) row.put("bet_level", row.path("level").asInt());
        if (!row.has("cc")) row.put("cc", "BRL");
        if (!row.has("cs")) row.put("cs", "R$");
        return row;
    }

    private ObjectNode balanceData(SessionState state) {
        return JSON.createObjectNode().put("balance", state.balance);
    }

    private ObjectNode sessionData(SessionState state) {
        return JSON.createObjectNode().put("session", state.key).put("balance", state.balance)
                .put("historyCount", state.history.size()).put("rulesHash", BlessingRulesMetadata.HASH)
                .put("gid", GID).put("generation", "realtime");
    }

    private SessionState session(HttpExchange exchange) {
        return session(exchange, query(exchange.getRequestURI().getRawQuery()));
    }

    private SessionState session(HttpExchange exchange, Map<String, String> values) {
        String key = firstNonBlank(values.get("token"), query(exchange.getRequestURI().getRawQuery()).get("token"),
                "local-replay");
        return SESSIONS.computeIfAbsent(key, k -> new SessionState(k, initialBalance));
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
        if ("/".equals(rawPath)) rawPath = "/index.html";
        if (rawPath.startsWith("/v2/1400/")) rawPath = rawPath.substring("/v2/1400".length());
        if (rawPath.equals("/v2/reportv2.js") || rawPath.equals("/../reportv2.js")) rawPath = "/reportv2.js";
        Path file = publishRoot.resolve(rawPath.substring(1)).normalize();
        if (!file.startsWith(publishRoot) || !Files.isRegularFile(file)) {
            sendError(exchange, 404, "static file not found");
            return;
        }
        byte[] body = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", mime(file));
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
        return query(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static ObjectNode ok() {
        return JSON.createObjectNode().put("code", 0).put("msg", "success");
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
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".ttf")) return "font/ttf";
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
            try {
                port = Integer.parseInt(authority.substring(colon + 1));
            } catch (NumberFormatException ignored) { }
        }
        return new HostPort(host, port);
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("options must be --name value");
            }
            result.put(args[i].substring(2), args[i + 1]);
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
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            result.load(reader);
        }
        return result;
    }

    private static Path resolvePublish(String value) throws Exception {
        if (value != null && !value.isBlank()) {
            Path direct = Path.of(value).toAbsolutePath().normalize();
            if (Files.isRegularFile(direct.resolve("index.html"))) return direct;
        }
        Path jar = Path.of(BlessingController.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath();
        Path root = Files.isRegularFile(jar) ? jar.getParent().getParent().getParent().getParent()
                : Path.of("D:/work/hd/cpgame");
        Path derived = root.resolve("publish/1400-Blessing-of-Ice-and-Fire").normalize();
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

    private static final class SessionState {
        final String key;
        BigDecimal balance;
        ObjectNode lastDelivery;
        final List<ObjectNode> history = new ArrayList<>();
        final Map<String, ObjectNode> idempotent = new LinkedHashMap<>();

        SessionState(String key, BigDecimal balance) {
            this.key = key;
            this.balance = balance.setScale(2, RoundingMode.HALF_UP);
        }
    }
}
