package com.cpgame.junglekings.api;

import com.cpgame.junglekings.CompleteRound;
import com.cpgame.junglekings.CompleteRoundFactory;
import com.cpgame.junglekings.GameRuleCore;
import com.cpgame.junglekings.IndependentVerifier;
import com.cpgame.junglekings.JungleKingsMultiplierCatalog;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
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

/**
 * Jungle Kings contract-v3 controller. Captures and fixtures are never loaded at runtime.
 * Every paid outcome is one-shot from the multiplier catalog. Demo does not read Redis.
 */
public final class ServerMain {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final IndependentVerifier VERIFIER = new IndependentVerifier();
    private static final ConcurrentHashMap<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, History> HISTORY = new ConcurrentHashMap<>();
    private static final List<String> HISTORY_ORDER = new ArrayList<>();
    private static final AtomicLong TRANSFER_SEQUENCE = new AtomicLong();
    private static Path publishRoot;
    private static long initialBalanceCents;

    private ServerMain() { }

    public static void main(String[] args) throws Exception {
        Path configPath = option(args, "--config", Path.of("server.properties")).toAbsolutePath().normalize();
        Properties config = load(configPath);
        int rawGameId = Integer.parseInt(config.getProperty("game.raw-id", "2"));
        if (rawGameId != GameRuleCore.RAW_GAME_ID) throw new IllegalArgumentException("game.raw-id 必须为 raw gid 2");
        initialBalanceCents = Long.parseLong(config.getProperty("runtime.initial-balance-cents", "1000000"));
        String host = option(args, "--server.address", config.getProperty("server.host", "0.0.0.0"));
        String portText = option(args, "--port", System.getenv("PORT"));
        if (portText == null || portText.isBlank()) {
            throw new IllegalArgumentException("contract v3 controller requires --port or PORT");
        }
        int port = Integer.parseInt(portText);
        if (port < 50000 || port > 59999) {
            throw new IllegalArgumentException("managed port must be in 50000-59999");
        }
        Path configuredPublish = Path.of(config.getProperty("publish.root", "../../../publish/2-Jungle-Kings"));
        Path defaultPublish = configuredPublish.isAbsolute() ? configuredPublish
                : configPath.getParent().resolve(configuredPublish);
        publishRoot = option(args, "--publish", defaultPublish).normalize().toAbsolutePath();

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/health", ServerMain::health);
        server.createContext("/api/balance", ServerMain::balance);
        server.createContext("/api/session", ServerMain::sessionInfo);
        server.createContext("/api/report/timing", ServerMain::timingReport);
        server.createContext("/cp/api/v1/auth/verify", ServerMain::verify);
        server.createContext("/cp/api/v1/auth/session", ServerMain::verify);
        server.createContext("/cp/api/v1/ping", exchange -> empty(exchange, 204));
        server.createContext("/cp/api/v1/jungle-kings/config", ServerMain::config);
        server.createContext("/cp/api/v1/jungle-kings/config-v2", ServerMain::config);
        server.createContext("/cp/api/v1/jungle-kings/spin", ServerMain::spin);
        server.createContext("/cp/api/v1/jungle-kings/spin-v2", ServerMain::spin);
        server.createContext("/cp/api/v1/jungle-kings/log-list", ServerMain::historyList);
        server.createContext("/cp/api/v1/jungle-kings/log-view", ServerMain::historyView);
        server.createContext("/Init", ServerMain::verify);
        server.createContext("/Config", ServerMain::config);
        server.createContext("/Spin", ServerMain::spin);
        server.createContext("/History", ServerMain::historyList);
        server.createContext("/", ServerMain::staticFile);
        server.setExecutor(Executors.newFixedThreadPool(12));
        server.start();
        System.out.println("CONTROLLER_READY rawGameId=2 contractVersion=3 port=" + port
                + " pid=" + ProcessHandle.current().pid() + " rulesHash=" + GameRuleCore.RULES_HASH
                + " publish=" + publishRoot + " generation=realtime");
    }

    private static void health(HttpExchange exchange) throws IOException {
        json(exchange, 200, "{\"status\":\"UP\",\"rawGameId\":2,\"contractVersion\":3,\"processMode\":\"managed-process\",\"generation\":\"realtime\",\"rulesHash\":"
                + Json.quote(GameRuleCore.RULES_HASH) + ",\"activeSessions\":" + SESSIONS.size() + "}");
    }

    private static void verify(HttpExchange exchange) throws IOException {
        if (!post(exchange)) return;
        Map<String, String> form = form(exchange);
        if (!rawGid(form)) { json(exchange, 400, error("gid 必须为 raw gid 2")); return; }
        String token = firstNonBlank(form.get("t"), form.get("token"), "local-gid2-" + UUID.randomUUID());
        Session session = SESSIONS.computeIfAbsent(token, ignored -> new Session(initialBalanceCents));
        String data = "{\"player\":{\"balance\":" + Json.quote(moneyCents(session.balanceCents))
                + ",\"id\":2,\"fbt\":0},\"token\":" + Json.quote(token)
                + ",\"t\":" + Json.quote(token) + ",\"ping\":{\"enable\":0,\"seconds\":60}}";
        json(exchange, 200, ok(data));
    }

    private static void config(HttpExchange exchange) throws IOException {
        if (!post(exchange)) return;
        Map<String, String> form = form(exchange);
        if (!rawGid(form)) { json(exchange, 400, error("gid 必须为 raw gid 2")); return; }
        String data = "{\"auto\":[10,30,50,100,500],\"auto_spin_num_list\":[10,30,50,100,500],"
                + "\"bll\":[1,2,3,4,5,6,7,8,9,10],\"bsl\":[0.5,5,50],\"cc\":\"BRL\",\"cs\":\"R$\","
                + "\"dbl\":1,\"dbs\":0.5,\"ls\":null,\"now_at\":" + Instant.now().getEpochSecond()
                + ",\"spl\":{\"S00011\":100,\"S00012\":50,\"S00013\":25,\"S00014\":5}}";
        json(exchange, 200, ok(data));
    }

    private static void spin(HttpExchange exchange) throws IOException {
        if (!post(exchange)) return;
        Map<String, String> form = form(exchange);
        if (!rawGid(form)) { json(exchange, 400, error("gid 必须为 raw gid 2")); return; }
        String token = firstNonBlank(form.get("t"), form.get("token"), "local-anonymous");
        Session session = SESSIONS.computeIfAbsent(token, ignored -> new Session(initialBalanceCents));
        String requestId = form.getOrDefault("request_id", "").strip();
        if (requestId.isEmpty()) requestId = UUID.randomUUID().toString();
        try {
            String response;
            synchronized (session) {
                response = session.replays.get(requestId);
                if (response == null) {
                    int betLevel = Integer.parseInt(firstNonBlank(
                            form.get("bet_level"), form.get("bl"), "1"));
                    BigDecimal betSize = new BigDecimal(firstNonBlank(
                            form.get("bet_size"), form.get("bs"), "0.5"));
                    List<String> chessboards = GameRuleCore.parseChessboards(form.get("ckl"));
                    GameRuleCore.validateBet(betSize, betLevel);
                    String rawOdd = firstNonBlank(form.get("odd"), form.get("odds"));
                    int requestedOdd = rawOdd == null
                            ? JungleKingsMultiplierCatalog.sampleRequestedOdd(RANDOM, chessboards)
                            : Integer.parseInt(rawOdd);
                    response = deliver(session, chessboards, betSize, betLevel, requestedOdd);
                    session.replays.put(requestId, response);
                    if (session.replays.size() > 2000) session.replays.clear();
                }
            }
            json(exchange, 200, response);
        } catch (IllegalArgumentException failure) {
            json(exchange, 400, error(failure.getMessage()));
        } catch (RuntimeException failure) {
            json(exchange, 503, error(failure.getMessage()));
        }
    }

    private static String deliver(Session session, List<String> chessboards,
                                  BigDecimal betSize, int betLevel, int requestedOdd) {
        CompleteRound round = generateRound(chessboards, betSize, betLevel, requestedOdd);
        long betCents = cents(round.betAmount());
        if (session.balanceCents < betCents) throw new IllegalArgumentException("余额不足");
        session.balanceCents -= betCents;
        session.balanceCents += cents(round.winAmount());
        String transferId = Long.toString(Instant.now().toEpochMilli() * 1000L
                + Math.floorMod(TRANSFER_SEQUENCE.incrementAndGet(), 1000L));
        long createdAt = Instant.now().getEpochSecond();
        History history = new History(transferId, createdAt, session.balanceCents, round);
        HISTORY.put(transferId, history);
        synchronized (HISTORY_ORDER) { HISTORY_ORDER.add(0, transferId); }
        return ok(spinJson(round, session.balanceCents));
    }

    private static CompleteRound generateRound(List<String> chessboards, BigDecimal betSize,
                                               int betLevel, int requestedOdd) {
        CompleteRound round = CompleteRoundFactory.generate(RANDOM, chessboards, betSize, betLevel, requestedOdd);
        VERIFIER.verify(round);
        int floored = JungleKingsMultiplierCatalog.floorOdd(chessboards, requestedOdd);
        if (round.multiplier() != floored) {
            throw new IllegalStateException("generated multiplier " + round.multiplier() + " != " + floored);
        }
        return round;
    }

    private static String spinJson(CompleteRound round, long balanceCents) {
        return "{\"bet_amount\":" + number(round.betAmount())
                + ",\"player\":{\"balance\":" + Json.quote(moneyCents(balanceCents)) + ",\"id\":2}"
                + ",\"rand_symbol_key_list\":" + boardsJson(round.boards())
                + ",\"win_amount\":" + number(round.winAmount())
                + ",\"win_payline_key_list\":" + Json.strings(round.winPaylineKeys())
                + ",\"win_symbol_key_list\":" + Json.strings(round.winSymbolKeys()) + "}";
    }

    private static void historyList(HttpExchange exchange) throws IOException {
        if (!post(exchange)) return;
        Map<String, String> form = form(exchange);
        if (!rawGid(form)) { json(exchange, 400, error("gid 必须为 raw gid 2")); return; }
        int page = positiveInt(form.getOrDefault("page_index", "1"), 1);
        List<String> ids;
        synchronized (HISTORY_ORDER) { ids = List.copyOf(HISTORY_ORDER); }
        int start = Math.min(ids.size(), (page - 1) * 10);
        int end = Math.min(ids.size(), start + 10);
        List<History> pageRows = ids.subList(start, end).stream().map(HISTORY::get).toList();
        BigDecimal totalBet = pageRows.stream().map(row -> row.round.betAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalWin = pageRows.stream().map(row -> row.round.winAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String data = "{\"bet_amount\":" + Json.quote(money(totalBet)) + ",\"log_count\":" + ids.size()
                + ",\"log_list\":[" + String.join(",", pageRows.stream().map(History::rowJson).toList()) + "]"
                + ",\"page_is_end\":" + (end >= ids.size() ? 1 : 0)
                + ",\"win_amount\":" + Json.quote(money(totalWin)) + "}";
        json(exchange, 200, ok(data));
    }

    private static void historyView(HttpExchange exchange) throws IOException {
        if (!post(exchange)) return;
        Map<String, String> form = form(exchange);
        if (!rawGid(form)) { json(exchange, 400, error("gid 必须为 raw gid 2")); return; }
        String transferId = firstNonBlank(form.get("transfer_id"), form.get("tis"), "");
        History history = HISTORY.get(transferId);
        if (history == null) { json(exchange, 404, error("History transfer_id 不存在")); return; }
        json(exchange, 200, ok(history.detailJson()));
    }

    private static void balance(HttpExchange exchange) throws IOException {
        Map<String, String> values = requestValues(exchange);
        Session session = SESSIONS.computeIfAbsent(values.getOrDefault("t", "local-anonymous"),
                ignored -> new Session(initialBalanceCents));
        synchronized (session) {
            json(exchange, 200, ok("{\"balance\":" + Json.quote(moneyCents(session.balanceCents)) + "}"));
        }
    }

    private static void sessionInfo(HttpExchange exchange) throws IOException {
        Map<String, String> values = requestValues(exchange);
        Session session = SESSIONS.computeIfAbsent(values.getOrDefault("t", "local-anonymous"),
                ignored -> new Session(initialBalanceCents));
        synchronized (session) {
            json(exchange, 200, ok("{\"rawGameId\":2,\"balance\":"
                    + Json.quote(moneyCents(session.balanceCents)) + "}"));
        }
    }

    private static void timingReport(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        empty(exchange, 204);
    }

    private static void staticFile(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) { empty(exchange, 204); return; }
        boolean head = exchange.getRequestMethod().equalsIgnoreCase("HEAD");
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET") && !head) { method(exchange, "GET"); return; }
        String requestPath = exchange.getRequestURI().getPath();
        if (requestPath.equals("/") || requestPath.isBlank()) requestPath = "/index.html";
        if (requestPath.equals("/2") || requestPath.equals("/2/")) requestPath = "/2/index.html";
        Path file = publishRoot.resolve(requestPath.substring(1)).normalize();
        if (!file.startsWith(publishRoot)) {
            text(exchange, 404, "Not found", "text/plain; charset=utf-8"); return;
        }
        if (Files.isDirectory(file)) file = file.resolve("index.html");
        if (!Files.isRegularFile(file)) {
            text(exchange, 404, "Not found", "text/plain; charset=utf-8"); return;
        }
        String type = contentType(file);
        byte[] data = Files.readAllBytes(file);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", type);
        headers.set("Cache-Control", "no-store");
        headers.set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, head ? -1 : data.length);
        if (!head) exchange.getResponseBody().write(data);
        exchange.close();
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".ico")) return "image/x-icon";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".wav")) return "audio/wav";
        if (name.endsWith(".ogg")) return "audio/ogg";
        if (name.endsWith(".ttf")) return "font/ttf";
        if (name.endsWith(".woff")) return "font/woff";
        if (name.endsWith(".woff2")) return "font/woff2";
        if (name.endsWith(".plist")) return "application/xml";
        String probed = null;
        try { probed = Files.probeContentType(file); } catch (IOException ignored) { }
        return probed != null ? probed : "application/octet-stream";
    }

    private static boolean rawGid(Map<String, String> form) { return "2".equals(form.get("gid")); }
    private static boolean post(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) { empty(exchange, 204); return false; }
        return method(exchange, "POST");
    }
    private static boolean method(HttpExchange exchange, String expected) throws IOException {
        if (exchange.getRequestMethod().equalsIgnoreCase(expected)) return true;
        exchange.getResponseHeaders().set("Allow", expected);
        json(exchange, 405, error("method not allowed"));
        return false;
    }
    private static Map<String, String> form(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String query = exchange.getRequestURI().getRawQuery();
        String joined = query == null || query.isBlank() ? body : body.isBlank() ? query : query + "&" + body;
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : joined.split("&")) if (!pair.isEmpty()) {
            int split = pair.indexOf('=');
            String key = split < 0 ? pair : pair.substring(0, split);
            String value = split < 0 ? "" : pair.substring(split + 1);
            values.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        String headerGid = exchange.getRequestHeaders().getFirst("game-id");
        String headerToken = exchange.getRequestHeaders().getFirst("web-token");
        if (headerGid != null) values.putIfAbsent("gid", headerGid);
        if (headerToken != null) values.putIfAbsent("t", headerToken);
        return values;
    }
    private static Map<String, String> requestValues(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) return Map.of();
        return form(exchange);
    }
    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        text(exchange, status, body, "application/json; charset=utf-8");
    }
    private static void text(HttpExchange exchange, int status, String body, String type) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", type);
        headers.set("Cache-Control", "no-store");
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Headers",
                "Content-Type, Authorization, game-id, web-token, Idempotency-Key, X-Session-Token");
        headers.set("Access-Control-Allow-Methods", "GET, POST, HEAD, OPTIONS");
        exchange.sendResponseHeaders(status, data.length);
        exchange.getResponseBody().write(data);
        exchange.close();
    }
    private static void empty(HttpExchange exchange, int status) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Headers",
                "Content-Type, Authorization, game-id, web-token, Idempotency-Key, X-Session-Token");
        headers.set("Access-Control-Allow-Methods", "GET, POST, HEAD, OPTIONS");
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
    private static String ok(String data) { return "{\"code\":200,\"data\":" + data + ",\"info\":\"ok\"}"; }
    private static String error(String message) {
        return "{\"code\":400,\"data\":{},\"info\":" + Json.quote(message == null ? "error" : message) + "}";
    }
    private static long cents(BigDecimal value) {
        return value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
    private static String moneyCents(long cents) {
        return BigDecimal.valueOf(cents, 2).setScale(2).toPlainString();
    }
    private static String number(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    private static String money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP).toPlainString(); }
    private static int positiveInt(String value, int fallback) {
        try { return Math.max(1, Integer.parseInt(value)); } catch (NumberFormatException ignored) { return fallback; }
    }
    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }
    private static Properties load(Path path) throws IOException {
        Properties result = new Properties();
        try (InputStream input = Files.newInputStream(path)) { result.load(input); }
        return result;
    }
    private static Path option(String[] args, String name, Path fallback) {
        for (String arg : args) if (arg.startsWith(name + "=")) return Path.of(arg.substring(name.length() + 1));
        for (int i = 0; i + 1 < args.length; i++) if (name.equals(args[i])) return Path.of(args[i + 1]);
        return fallback;
    }
    private static String option(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith(name + "=")) return args[i].substring(name.length() + 1);
            if (name.equals(args[i]) && i + 1 < args.length) return args[i + 1];
        }
        return fallback;
    }

    private static String boardsJson(List<List<String>> boards) {
        List<String> outer = new ArrayList<>();
        for (List<String> board : boards) outer.add(Json.strings(board));
        return "[" + String.join(",", outer) + "]";
    }

    private static final class Session {
        private long balanceCents;
        private final Map<String, String> replays = new LinkedHashMap<>();
        private Session(long balanceCents) { this.balanceCents = balanceCents; }
    }

    private record History(String transferId, long createdAt, long balanceAfterCents, CompleteRound round) {
        String rowJson() {
            return "{\"bet_amount\":" + Json.quote(money(round.betAmount()))
                    + ",\"bid\":" + Json.quote("2-" + transferId)
                    + ",\"created_at\":" + createdAt
                    + ",\"game_type\":2,\"tis\":" + Json.quote(transferId)
                    + ",\"transfer_id\":" + transferId
                    + ",\"win_amount\":" + Json.quote(money(round.winAmount())) + "}";
        }

        String detailJson() {
            List<String> paylineObjects = new ArrayList<>();
            BigDecimal lineStake = GameRuleCore.lineStake(round.betSize(), round.betLevel());
            for (int i = 0; i < round.winPaylineKeys().size(); i++) {
                String symbol = round.winSymbolKeys().get(i);
                BigDecimal lineWin = lineStake.multiply(
                        BigDecimal.valueOf(GameRuleCore.payMultiplier(symbol)));
                paylineObjects.add("{\"payline_key\":" + Json.quote(round.winPaylineKeys().get(i))
                        + ",\"win_amount\":" + Json.quote(money(lineWin)) + "}");
            }
            return "{\"balance_after\":" + Json.quote(moneyCents(balanceAfterCents))
                    + ",\"bet_amount\":" + number(round.betAmount())
                    + ",\"bet_level\":" + round.betLevel()
                    + ",\"bet_size\":" + Json.quote(money(round.betSize()))
                    + ",\"bid\":" + Json.quote("2-" + transferId)
                    + ",\"chessboard_key\":" + Json.quote(String.join(",", round.chessboards()))
                    + ",\"created_at\":" + createdAt
                    + ",\"rand_symbol_key_list\":" + boardsJson(round.boards())
                    + ",\"win_amount\":" + Json.quote(money(round.winAmount()))
                    + ",\"win_payline_list\":[" + String.join(",", paylineObjects) + "]"
                    + ",\"win_symbol_key_list\":" + Json.strings(round.winSymbolKeys()) + "}";
        }
    }

    static final class Json {
        private Json() { }
        static String quote(String value) {
            StringBuilder result = new StringBuilder("\"");
            for (char c : value.toCharArray()) switch (c) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> result.append(c);
            }
            return result.append('"').toString();
        }
        static String strings(List<String> values) {
            return "[" + String.join(",", values.stream().map(Json::quote).toList()) + "]";
        }
    }
}
