package com.cpgame.replica.freedomday;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayBoard;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayBoardGenerator;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayIndependentLossGenerator;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayOrdinaryLossPolicy;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayResultUtil;
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

/** Freedom Day Controller v3：静态页面与 API 共用唯一受管 PID、唯一监听端口。 */
public final class FreedomDayController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, SessionState> SESSIONS = new ConcurrentHashMap<>();
    private static final int PROVIDER_GID = 2260;

    private final Path publishRoot;
    private final BigDecimal initialBalance;
    private final int maxConsecutiveWins;
    private final int maxMarySpins;

    private FreedomDayController(Path publishRoot, Properties config) {
        this.publishRoot = publishRoot.toAbsolutePath().normalize();
        this.initialBalance = new BigDecimal(config.getProperty("session.initial-balance", "1000.00"));
        this.maxConsecutiveWins = Integer.parseInt(config.getProperty("round.max-consecutive-wins", "10"));
        this.maxMarySpins = Integer.parseInt(config.getProperty("round.max-mary-spins", "30"));
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        int port = requiredPort(options);
        Properties config = loadConfig(options.get("config"));
        Path publish = resolvePublish(options.getOrDefault("publish", config.getProperty("publish.directory", "")));
        FreedomDayController controller = new FreedomDayController(publish, config);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", controller::handle);
        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0), "freedom-day-controller-stop"));
        server.start();
        System.out.printf("CONTROLLER_READY gameId=1809 providerGid=%d port=%d pid=%d rulesVersion=%s rulesHash=%s publish=%s%n",
                PROVIDER_GID, port, ProcessHandle.current().pid(), FreedomDayRulesMetadata.VERSION,
                FreedomDayRulesMetadata.HASH, publish);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) { sendEmpty(exchange, 204); return; }
            String path = exchange.getRequestURI().getPath();
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
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
            boolean featureBuy = "3".equals(form.get("bet_type"));
            BigDecimal betSize = decimal(form.getOrDefault("bet_gold", "0.01"), "bet_gold");
            int level = positiveInt(form.getOrDefault("level", "1"), "level");
            if (state.active == null) state.active = generateRound(betSize, level, featureBuy);
            ObjectNode settled = settleNext(state);
            ObjectNode response = ok(); response.set("data", settled);
            if (idempotencyKey != null) state.idempotent.put(idempotencyKey, response.deepCopy());
            return response;
        }
    }

    private ActiveRound generateRound(BigDecimal betSize, int level, boolean featureBuy) throws Exception {
        BigDecimal unitBet = betSize.multiply(BigDecimal.valueOf(level));
        CompleteRoundFactory factory = new CompleteRoundFactory();
        CompleteRoundFactory.GeneratedRound generated;
        while (true) {
            try { generated = factory.generate(RANDOM, featureBuy, maxConsecutiveWins, maxMarySpins); break; }
            catch (CompleteRoundFactory.RoundRejectedException ignored) { }
        }
        CompleteRoundCodec codec = new CompleteRoundCodec();
        RoundVerification verification = codec.verify(generated.fact(), maxConsecutiveWins, maxMarySpins);
        if (verification.multiplier().compareTo(generated.multiplier()) != 0) {
            throw new IllegalStateException("runtime complete Round failed independent verification");
        }
        long seedMarker = RANDOM.nextLong();
        ArrayNode templates = (ArrayNode) JSON.readTree(FreedomDayRoundProtocolCli.protocol(
                generated.fact(), unitBet, level, seedMarker));
        List<ObjectNode> deliveries = new ArrayList<>();
        templates.forEach(node -> { ObjectNode value = (ObjectNode) node.deepCopy(); value.remove("_seed"); deliveries.add(value); });
        return new ActiveRound(UUID.randomUUID().toString(), List.copyOf(deliveries), 0);
    }

    private ObjectNode settleNext(SessionState state) {
        ActiveRound active = state.active;
        int deliveryIndex = active.nextIndex;
        ObjectNode value = active.deliveries.get(deliveryIndex).deepCopy();
        BigDecimal unitBet = value.remove("_unit_bet").decimalValue();
        boolean featureBuy = value.remove("_feature_buy").asBoolean();
        int freeIndex = value.remove("_free_index").asInt();
        int freeTotal = value.remove("_free_total").asInt();
        BigDecimal cumulative = value.remove("_cumulative_free_win").decimalValue();
        int endingMultiplier = value.remove("_ending_multiplier").asInt();
        value.remove("_awarded_free_spins");
        BigDecimal charged = freeIndex == 0
                ? unitBet.multiply(BigDecimal.valueOf(20L * (featureBuy ? 75L : 1L))) : BigDecimal.ZERO;
        BigDecimal spinWin = value.path("total_win").decimalValue();
        BigDecimal start = state.balance;
        state.balance = start.subtract(charged).add(spinWin).setScale(2, RoundingMode.HALF_UP);
        value.put("order_id", value.path("oid").asText());
        value.put("bet_gold", charged); value.put("change_gold", spinWin.subtract(charged));
        value.put("start_gold", start); value.put("end_gold", state.balance);
        value.put("odds", charged.signum() == 0 ? BigDecimal.ZERO
                : spinWin.divide(charged, 6, RoundingMode.HALF_UP));
        ObjectNode extend = value.putObject("extend");
        extend.put("act_bet_gold", 0).put("act_id", "0").put("bet_type", featureBuy ? 3 : 0);
        if (freeTotal == 0 && !featureBuy) {
            value.put("frees", false);
        } else {
            ObjectNode frees = value.putObject("frees");
            frees.put("tt", freeTotal).put("st", Math.max(0, freeTotal - freeIndex)).put("twa", cumulative)
                    .put("lwa", freeIndex > 0 ? spinWin : BigDecimal.ZERO).put("m", endingMultiplier)
                    .put("ba", unitBet.multiply(BigDecimal.valueOf(20L * (featureBuy ? 75L : 1L))))
                    .put("bet", unitBet);
        }
        value.put("small_game_type", 0);
        value.put("roundKey", active.roundKey).put("deliveryIndex", deliveryIndex)
                .put("deliveryCount", active.deliveries.size()).put("_source", "formal-java-rule-core");
        active.nextIndex++;
        state.lastDelivery = value.deepCopy();
        state.history.add(value.deepCopy());
        if (active.nextIndex >= active.deliveries.size()) state.active = null;
        return value;
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

    private ObjectNode initialBoard(BigDecimal balance) throws Exception {
        FreedomDayIndependentLossGenerator losses = new FreedomDayIndependentLossGenerator();
        FreedomDayBoard baseline = losses.generate(RANDOM, false);
        FreedomDayBoard board = FreedomDayOrdinaryLossPolicy.halveBallOccurrence(baseline, RANDOM,
                FreedomDayBoardGenerator.defaultNormalWeights(), FreedomDayBoardGenerator.defaultFreeWeights());
        CompleteRoundFact.BoardFact page = new CompleteRoundFact.BoardFact(ints(board.getProp()), ints(board.getTrl()),
                board.getGrids(), board.getGoldFrames(), board.getSilverFrames());
        CompleteRoundFact fact = new CompleteRoundFact(1, false, List.of(List.of(page)));
        ObjectNode data = (ObjectNode) JSON.readTree(FreedomDayRoundProtocolCli.protocol(
                fact, new BigDecimal("0.01"), 1, RANDOM.nextLong())).get(0);
        data.remove(List.of("_unit_bet", "_feature_buy", "_free_index", "_free_total", "_cumulative_free_win",
                "_ending_multiplier", "_awarded_free_spins", "_seed"));
        data.put("oid", "INIT-1809").put("order_id", "INIT-1809").put("bet_gold", 0)
                .put("change_gold", 0).put("win_gold", 0).put("total_win", 0).put("end_gold", balance)
                .put("small_game_type", 0).put("frees", false);
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
        game.put("gid", PROVIDER_GID).put("game_name", "Freedom Day").put("default_bet_gold", 0.01)
                .put("default_level", 1).put("least_gold", 0.01).put("buy_free_max_bet", 0);
        game.set("bet_gold", JSON.valueToTree(List.of(0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1)));
        ObjectNode way = game.putArray("game_way").addObject();
        way.put("way_id", 226010000).put("min_bet_gold", 0.01).put("max_bet_gold", 1000);
        ObjectNode gs = data.putObject("game_server");
        gs.put("ngs_switch", 0).put("gs_host1", hp.host).put("gs_port1", hp.port).put("gs_sport1", 0)
                .put("gs_push_host", hp.host).put("gs_push_port", hp.port).put("gs_push_sport", 0)
                .put("gos_host", hp.host).put("gos_port", hp.port).put("gos_sport", 0)
                .put("ps_host", hp.host).put("ps_port", hp.port);
        ObjectNode response = ok(); response.set("data", data); return response;
    }

    private ObjectNode userResponse(HttpExchange exchange, SessionState state) {
        HostPort hp = hostPort(exchange);
        ObjectNode data = JSON.createObjectNode();
        data.put("token", state.key).put("uid", 1809001).put("user_id", 1809001).put("nickname", "Freedom Day Demo")
                .put("gold", state.balance).put("currency", "BRL").put("currency_symbol", "R$")
                .put("ip", hp.host).put("room_mode", 0);
        ObjectNode response = ok(); response.set("data", data); return response;
    }

    private ObjectNode activityResponse() {
        ObjectNode data = JSON.createObjectNode();
        ObjectNode free = data.putObject("free"); free.putArray("act_list"); free.put("invite_act_have", 0).put("invite_end_time", 0);
        ObjectNode response = ok(); response.set("data", data); return response;
    }

    private ObjectNode historySummary(SessionState state) {
        synchronized (state) {
            BigDecimal bet = state.history.stream().map(n -> n.path("bet_gold").decimalValue()).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal change = state.history.stream().map(n -> n.path("change_gold").decimalValue()).reduce(BigDecimal.ZERO, BigDecimal::add);
            ObjectNode data = historyData(bet, change);
            if (!state.history.isEmpty()) data.withArray("list").addObject()
                    .put("day", Instant.now().getEpochSecond() / 86400 * 86400).put("bet_gold", bet).put("change_gold", change);
            ObjectNode response = ok(); response.set("data", data); return response;
        }
    }

    private ObjectNode historyDetail(SessionState state, Map<String, String> form) {
        int page = positiveInt(form.getOrDefault("page", "1"), "page");
        int pageSize = positiveInt(form.getOrDefault("page_size", "30"), "page_size");
        synchronized (state) {
            List<ObjectNode> ordered = state.history.stream().sorted(Comparator.comparingLong(n -> -n.path("time").asLong())).toList();
            BigDecimal bet = ordered.stream().map(n -> n.path("bet_gold").decimalValue()).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal change = ordered.stream().map(n -> n.path("change_gold").decimalValue()).reduce(BigDecimal.ZERO, BigDecimal::add);
            ObjectNode data = historyData(bet, change); ArrayNode list = data.withArray("list");
            int from = Math.min(ordered.size(), (page - 1) * pageSize), to = Math.min(ordered.size(), from + pageSize);
            for (ObjectNode spin : ordered.subList(from, to)) {
                ObjectNode row = list.addObject();
                row.put("order_id", spin.path("order_id").asText()).put("time", spin.path("time").asLong())
                        .put("bet", spin.path("bet_gold").decimalValue()).put("bet_gold", spin.path("bet_gold").decimalValue())
                        .put("change_gold", spin.path("change_gold").decimalValue()).put("type", spin.path("type").asInt())
                        .put("roundKey", spin.path("roundKey").asText()).put("deliveryIndex", spin.path("deliveryIndex").asInt());
                row.set("extend", spin.path("extend").deepCopy());
                ObjectNode result = row.putArray("results").addObject();
                result.put("time", spin.path("time").asLong()).put("end_gold", spin.path("end_gold").decimalValue())
                        .put("level", spin.path("level").asInt()).put("change_gold", spin.path("change_gold").decimalValue())
                        .put("bet_gold", spin.path("bet_gold").decimalValue());
                result.set("result", spin.path("props").deepCopy());
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
        FreedomDayResultUtil.copyPayTable().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ObjectNode symbol = table.addObject(); symbol.put("prop_id", entry.getKey()); ArrayNode odds = symbol.putArray("odds");
            int[] values = entry.getValue(); for (int i = 0; i < values.length; i++) odds.addObject().put("num", i + 3).put("odds", values[i]);
        }); return table;
    }

    private ObjectNode balanceData(SessionState state) { return JSON.createObjectNode().put("balance", state.balance); }
    private ObjectNode sessionData(SessionState state) {
        ObjectNode data = JSON.createObjectNode().put("session", state.key).put("balance", state.balance)
                .put("historyCount", state.history.size()).put("rulesHash", FreedomDayRulesMetadata.HASH);
        if (state.active != null) data.put("roundKey", state.active.roundKey).put("nextDeliveryIndex", state.active.nextIndex)
                .put("deliveryCount", state.active.deliveries.size());
        return data;
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
        String rawPath = uri.getPath(); if ("/".equals(rawPath)) rawPath = "/index.html";
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
        q.putIfAbsent("ai", "luck_single_10229"); q.putIfAbsent("btt", "1"); q.put("gid", Integer.toString(PROVIDER_GID));
        q.putIfAbsent("l", "pt"); q.putIfAbsent("language", "pt-br"); q.put("sip", hostPort(exchange).authority());
        q.putIfAbsent("t", "local-replay"); q.putIfAbsent("token", "local-replay");
        return q.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).reduce((a,b) -> a + "&" + b).orElse("");
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
        ObjectNode body = JSON.createObjectNode().put("code", status).put("msg", message); sendJsonStatus(exchange, status, body);
    }
    private static void sendJsonStatus(HttpExchange exchange, int status, JsonNode value) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); send(exchange, status, JSON.writeValueAsBytes(value));
    }
    private static void sendEmpty(HttpExchange exchange, int status) throws IOException { send(exchange, status, new byte[0]); }
    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        Headers h = exchange.getResponseHeaders(); h.set("Cache-Control", "no-store"); h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS"); h.set("Access-Control-Allow-Headers", "Content-Type, Idempotency-Key");
        exchange.sendResponseHeaders(status, body.length); if (body.length > 0) exchange.getResponseBody().write(body);
    }

    private static String mime(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) return "text/html; charset=utf-8"; if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8"; if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg"; if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".ttf")) return "font/ttf"; return "application/octet-stream";
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
        Path jar = Path.of(FreedomDayController.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
        Path root = Files.isRegularFile(jar) ? jar.getParent().getParent().getParent().getParent() : Path.of("D:/work/hd/cpgame");
        Path derived = root.resolve("publish/1809-mac-Freedom-Day").normalize();
        if (!Files.isRegularFile(derived.resolve("index.html"))) throw new IllegalArgumentException("publish/index.html not found: " + derived);
        return derived;
    }
    private static BigDecimal decimal(String value, String name) { BigDecimal result = new BigDecimal(value); if (result.signum() <= 0) throw new IllegalArgumentException(name + " must be positive"); return result; }
    private static int positiveInt(String value, String name) { int result = Integer.parseInt(value); if (result <= 0) throw new IllegalArgumentException(name + " must be positive"); return result; }
    private static String firstNonBlank(String... values) { for (String value : values) if (value != null && !value.isBlank()) return value; return null; }
    private static List<Integer> ints(int[] values) { List<Integer> out = new ArrayList<>(); for (int value : values) out.add(value); return out; }

    private record HostPort(String host, int port) { String authority() { return host + ":" + port; } }
    private static final class ActiveRound {
        final String roundKey; final List<ObjectNode> deliveries; int nextIndex;
        ActiveRound(String roundKey, List<ObjectNode> deliveries, int nextIndex) { this.roundKey = roundKey; this.deliveries = deliveries; this.nextIndex = nextIndex; }
    }
    private static final class SessionState {
        final String key; BigDecimal balance; ActiveRound active; ObjectNode lastDelivery;
        final List<ObjectNode> history = new ArrayList<>(); final Map<String, ObjectNode> idempotent = new LinkedHashMap<>();
        SessionState(String key, BigDecimal balance) { this.key = key; this.balance = balance.setScale(2, RoundingMode.HALF_UP); }
    }
}
