package com.cpgame.junglefruit.api;

import com.cpgame.batcha.g16.CompleteRound;
import com.cpgame.batcha.g16.GameRuleCore;
import com.cpgame.batcha.g16.IndependentVerifier;
import com.cpgame.batcha.g16.MemberCodec;
import com.cpgame.batcha.g16.RedisRespRoundStore;
import com.cpgame.batcha.g16.RedisRoundStore;
import com.cpgame.batcha.g16.RedisRoundWriter;
import com.cpgame.batcha.g16.RoundMode;
import com.cpgame.batcha.g16.Step;
import com.cpgame.batcha.g16.WinMatch;
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
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Jungle Fruit local protocol server. Captures and fixtures are never loaded at runtime.
 * Every outcome, including ordinary LOSS, is LINDEX-read from the formal
 * Loader's pre-generated PerKeyList/MaryKeyList pools. Captures, fixtures and runtime generation are
 * deliberately absent from this controller.
 */
public final class ServerMain {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final MemberCodec CODEC = new MemberCodec();
    private static final IndependentVerifier VERIFIER = new IndependentVerifier(new BigDecimal("20000"), 18, 25);
    private static final ConcurrentHashMap<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, History> HISTORY = new ConcurrentHashMap<>();
    private static final List<String> HISTORY_ORDER = new ArrayList<>();
    private static final AtomicLong TRANSFER_SEQUENCE = new AtomicLong();
    private static Path publishRoot;
    private static RedisConfig redis;
    private static long initialBalanceCents;

    private ServerMain() { }

    public static void main(String[] args) throws Exception {
        Path configPath = option(args, "--config", Path.of("server.properties")).toAbsolutePath().normalize();
        Properties config = load(configPath);
        int rawGameId = Integer.parseInt(config.getProperty("game.raw-id", "16"));
        if (rawGameId != GameRuleCore.RAW_GAME_ID) throw new IllegalArgumentException("game.raw-id 必须为 raw gid 16");
        redis = RedisConfig.from(config);
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
        Path configuredPublish = Path.of(config.getProperty("publish.root", "../../../publish/16-Jungle-Fruit"));
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
        server.createContext("/cp/api/v1/jungle-fruit/config", ServerMain::config);
        server.createContext("/cp/api/v1/jungle-fruit/spin", ServerMain::spin);
        server.createContext("/cp/api/v1/jungle-fruit/log-list", ServerMain::historyList);
        server.createContext("/cp/api/v1/jungle-fruit/log-view", ServerMain::historyView);
        server.createContext("/Init", ServerMain::verify);
        server.createContext("/Config", ServerMain::config);
        server.createContext("/Spin", ServerMain::spin);
        server.createContext("/History", ServerMain::historyList);
        server.createContext("/", ServerMain::staticFile);
        server.setExecutor(Executors.newFixedThreadPool(12));
        server.start();
        System.out.println("CONTROLLER_READY rawGameId=16 contractVersion=3 port=" + port
            + " pid=" + ProcessHandle.current().pid() + " rulesHash=" + GameRuleCore.RULES_HASH
            + " publish=" + publishRoot);
    }

    private static void health(HttpExchange exchange) throws IOException {
        boolean redisReachable;
        int pools = 0;
        try (RedisRoundStore store = openRedisStore()) {
            RedisRoundWriter writer = new RedisRoundWriter(store, CODEC, VERIFIER, 300);
            pools += (int) writer.poolSize(false).buckets();
            pools += (int) writer.poolSize(true).buckets();
            redisReachable = true;
        } catch (IOException failure) {
            redisReachable = false;
        }
        json(exchange, 200, "{\"status\":\"UP\",\"rawGameId\":16,\"contractVersion\":3,\"processMode\":\"managed-process\",\"rulesHash\":"
            + Json.quote(GameRuleCore.RULES_HASH) + ",\"redisReachable\":" + redisReachable
            + ",\"redisPools\":" + pools + ",\"activeSessions\":" + SESSIONS.size() + "}");
    }

    private static void verify(HttpExchange exchange) throws IOException {
        if (!post(exchange)) return;
        Map<String, String> form = form(exchange);
        if (!rawGid(form)) { json(exchange, 400, error("gid 必须为 raw gid 16")); return; }
        String token = "local-gid16-" + UUID.randomUUID();
        Session session = new Session(initialBalanceCents);
        SESSIONS.put(token, session);
        String data = "{\"player\":{\"balance\":" + Json.quote(moneyCents(session.balanceCents))
            + ",\"id\":16},\"token\":" + Json.quote(token)
            + ",\"t\":" + Json.quote(token) + ",\"ping\":{\"enable\":0,\"seconds\":60}}";
        json(exchange, 200, ok(data));
    }

    private static void config(HttpExchange exchange) throws IOException {
        if (!post(exchange)) return;
        Map<String, String> form = form(exchange);
        if (!rawGid(form)) { json(exchange, 400, error("gid 必须为 raw gid 16")); return; }
        String data = "{\"auto_spin_num_list\":[10,30,50,100,500],"
            + "\"bet_level_list\":[1,2,3,4,5,6,7,8,9,10],"
            + "\"bet_size_list\":[0.05,0.5,2.5],\"cc\":\"BRL\",\"cs\":\"R$\","
            + "\"default_bet_level\":2.5,\"default_bet_size\":0.05,\"last_spin\":null,"
            + "\"now_at\":" + Instant.now().getEpochSecond() + ",\"symbol_pay_list\":" + paytableJson() + "}";
        json(exchange, 200, ok(data));
    }

    private static void spin(HttpExchange exchange) throws IOException {
        if (!post(exchange)) return;
        Map<String, String> form = form(exchange);
        if (!rawGid(form)) { json(exchange, 400, error("gid 必须为 raw gid 16")); return; }
        String token = form.getOrDefault("t", "").strip();
        Session session = SESSIONS.computeIfAbsent(token.isEmpty() ? "local-anonymous" : token,
            ignored -> new Session(initialBalanceCents));
        String requestId = form.getOrDefault("request_id", "").strip();
        if (requestId.isEmpty()) requestId = UUID.randomUUID().toString();
        try {
            String response;
            synchronized (session) {
                response = session.replays.get(requestId);
                if (response == null) {
                    int betLevel = Integer.parseInt(form.getOrDefault("bet_level", form.getOrDefault("bl", "1")));
                    BigDecimal betSize = new BigDecimal(form.getOrDefault("bet_size", form.getOrDefault("bs", "0.05")));
                    GameRuleCore.validateBet(betSize, betLevel);
                    if (session.active == null) beginRound(session, betSize, betLevel, requestedMode(form.get("scenario")));
                    response = deliver(session);
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

    private static void beginRound(Session session, BigDecimal betSize, int betLevel, RoundMode requestedMode) {
        BigDecimal requestedBet = betSize.multiply(BigDecimal.valueOf(20L * betLevel));
        long requestedBetCents = cents(requestedBet);
        if (session.balanceCents < requestedBetCents) throw new IllegalArgumentException("余额不足");
        CompleteRound member = claimFormalRound(requestedMode);
        // Reprice the same claimed boards and state facts through the shared core.
        // No board generation or additional Redis claim occurs for a different configured bet.
        List<Step> facts = member.steps().stream().map(step -> Step.fact(
            step.deliveryIndex(), step.deliveryIndex() == 0 ? requestedBet : BigDecimal.ZERO,
            betSize, betLevel, step.symbols(), step.spinStatus(), step.freeSpinNum(),
            step.nowFreeSpinCount(), step.smallGameType())).toList();
        CompleteRound round = GameRuleCore.materialize(requestedBet, betSize, betLevel, facts);
        VERIFIER.verify(round);
        long betCents = cents(round.paidBet());
        long balanceAfterBet = session.balanceCents - betCents;
        session.balanceCents = balanceAfterBet;
        String transferId = Long.toString(Instant.now().toEpochMilli() * 1000L
            + Math.floorMod(TRANSFER_SEQUENCE.incrementAndGet(), 1000L));
        session.active = new ActiveRound(round, transferId, Instant.now().getEpochSecond(),
            balanceAfterBet, 0, BigDecimal.ZERO, new ArrayList<>());
    }

    private static CompleteRound claimFormalRound(RoundMode requestedMode) {
        try (RedisRoundStore store = openRedisStore()) {
            RedisRoundWriter writer = new RedisRoundWriter(store, CODEC, VERIFIER, 300);
            List<RoundMode> modes;
            if (requestedMode != null) modes = List.of(requestedMode);
            else modes = RANDOM.nextBoolean()
                ? List.of(RoundMode.WIN, RoundMode.MARY, RoundMode.FREE)
                : List.of(RoundMode.LOSS);
            return writer.claimAny(modes, RANDOM).orElseThrow(() ->
                new IllegalStateException("Redis complete-Round pool is empty for " + modes)).round();
        } catch (IOException unavailable) {
            throw new IllegalStateException("Redis complete-Round source unavailable", unavailable);
        }
    }

    private static RoundMode requestedMode(String value) {
        if (value == null || value.isBlank() || "RANDOM".equalsIgnoreCase(value)) return null;
        return switch (value.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "LOSS", "ORDINARY_LOSS" -> RoundMode.LOSS;
            case "WIN", "ORDINARY_WIN" -> throw new IllegalArgumentException("EXCLUSIVE_ORDINARY_WIN_UNOBSERVED; use evidenced MARY cascade mode");
            case "MARY", "MARY_SMALL_GAME" -> RoundMode.MARY;
            case "FREE", "SCATTER_FREE_ROUNDS" -> RoundMode.FREE;
            default -> throw new IllegalArgumentException("unknown scenario: " + value);
        };
    }

    private static RedisRoundStore openRedisStore() throws IOException {
        return new RedisRespRoundStore(redis.host(), redis.port(), redis.password(), redis.database(),
            redis.timeoutMillis(), redis.timeoutMillis());
    }

    private static String deliver(Session session) {
        ActiveRound active = session.active;
        Step step = active.round.steps().get(active.deliveryIndex);
        BigDecimal credited = step.spinStatus() == 1 ? step.roundWinAmount() : BigDecimal.ZERO;
        session.balanceCents += cents(credited);
        BigDecimal freeTotal = active.freePayout.add(step.smallGameType() == 2 && step.spinStatus() == 1
            ? step.roundWinAmount() : BigDecimal.ZERO);
        String stepJson = stepJson(step, session.balanceCents, active.transferId, active.createdAt, freeTotal);
        active.emittedSteps.add(stepJson);
        int next = active.deliveryIndex + 1;
        if (next >= active.round.steps().size()) {
            History history = new History(active.transferId, active.createdAt, active.balanceAfterBet,
                active.round, List.copyOf(active.emittedSteps));
            HISTORY.put(active.transferId, history);
            synchronized (HISTORY_ORDER) { HISTORY_ORDER.add(0, active.transferId); }
            session.active = null;
        } else {
            session.active = new ActiveRound(active.round, active.transferId, active.createdAt,
                active.balanceAfterBet, next, freeTotal, active.emittedSteps);
        }
        return ok(stepJson);
    }

    private static void historyList(HttpExchange exchange) throws IOException {
        if (!post(exchange)) return;
        Map<String, String> form = form(exchange);
        if (!rawGid(form)) { json(exchange, 400, error("gid 必须为 raw gid 16")); return; }
        int page = positiveInt(form.getOrDefault("page_index", "1"), 1);
        List<String> ids;
        synchronized (HISTORY_ORDER) { ids = List.copyOf(HISTORY_ORDER); }
        int start = Math.min(ids.size(), (page - 1) * 10);
        int end = Math.min(ids.size(), start + 10);
        List<History> pageRows = ids.subList(start, end).stream().map(HISTORY::get).toList();
        BigDecimal totalBet = pageRows.stream().map(row -> row.round.paidBet()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalWin = pageRows.stream().map(row -> row.round.payout()).reduce(BigDecimal.ZERO, BigDecimal::add);
        String data = "{\"bet_amount\":" + Json.quote(money(totalBet)) + ",\"log_count\":" + ids.size()
            + ",\"log_list\":[" + String.join(",", pageRows.stream().map(History::rowJson).toList()) + "]"
            + ",\"page_is_end\":" + (end >= ids.size() ? 1 : 0) + ",\"win_amount\":" + Json.quote(money(totalWin)) + "}";
        json(exchange, 200, ok(data));
    }

    private static void historyView(HttpExchange exchange) throws IOException {
        if (!post(exchange)) return;
        Map<String, String> form = form(exchange);
        if (!rawGid(form)) { json(exchange, 400, error("gid 必须为 raw gid 16")); return; }
        History history = HISTORY.get(form.getOrDefault("transfer_id", ""));
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
            String active = session.active == null ? "null" : "{\"mode\":"
                + Json.quote(session.active.round.mode().name()) + ",\"deliveryIndex\":"
                + session.active.deliveryIndex + ",\"deliveryCount\":"
                + session.active.round.steps().size() + "}";
            json(exchange, 200, ok("{\"rawGameId\":16,\"balance\":"
                + Json.quote(moneyCents(session.balanceCents)) + ",\"activeRound\":" + active + "}"));
        }
    }

    private static void timingReport(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        empty(exchange, 204);
    }

    private static String stepJson(Step step, long balanceCents, String transferId,
                                   long createdAt, BigDecimal freeTotal) {
        int gameType = step.smallGameType() == 2 && step.nowFreeSpinCount() > 0 ? 2 : 1;
        StringBuilder json = new StringBuilder("{");
        if (gameType == 2) json.append("\"all_free_win_amount\":").append(number(freeTotal)).append(',');
        json.append("\"bet_amount\":").append(number(step.betAmount()))
            .append(",\"bet_level\":").append(step.betLevel())
            .append(",\"bet_size\":").append(number(step.betSize()))
            .append(",\"created_at\":").append(createdAt)
            .append(",\"free_spin_num\":").append(step.freeSpinNum())
            .append(",\"game_type\":").append(gameType)
            .append(",\"now_free_spin_count\":").append(step.nowFreeSpinCount())
            .append(",\"player\":{\"balance\":").append(Json.quote(moneyCents(balanceCents))).append(",\"id\":16}")
            .append(",\"rand_symbol_key_list\":").append(Json.strings(step.symbols()))
            .append(",\"round_win_amount\":").append(number(step.roundWinAmount()))
            .append(",\"small_game_type\":").append(step.smallGameType())
            .append(",\"spin_status\":").append(step.spinStatus())
            .append(",\"win_amount\":").append(number(step.winAmount()))
            .append(",\"win_match_key_list\":").append(winMatches(step.winMatches()))
            .append(",\"win_symbol_key_list\":").append(Json.strings(step.winMatches().stream().map(WinMatch::symbolKey).toList()))
            .append(",\"win_x_key_list\":").append(Json.strings(step.winXKeys())).append('}');
        return json.toString();
    }

    private static String paytableJson() {
        List<String> symbols = new ArrayList<>();
        GameRuleCore.symbolPayTable().forEach((symbol, pays) -> {
            List<String> entries = new ArrayList<>();
            pays.forEach((size, units) -> entries.add(Json.quote(Integer.toString(size)) + ":" + units));
            symbols.add(Json.quote(symbol) + ":{" + String.join(",", entries) + "}");
        });
        return "{" + String.join(",", symbols) + "}";
    }

    private static String winMatches(List<WinMatch> matches) {
        return "[" + String.join(",", matches.stream().map(match -> "["
            + String.join(",", match.indices().stream().map(String::valueOf).toList()) + "]").toList()) + "]";
    }

    private static void staticFile(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) { empty(exchange, 204); return; }
        boolean head = exchange.getRequestMethod().equalsIgnoreCase("HEAD");
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET") && !head) { method(exchange, "GET"); return; }
        String requestPath = exchange.getRequestURI().getPath();
        if (requestPath.equals("/")) requestPath = "/index.html";
        Path file = publishRoot.resolve(requestPath.substring(1)).normalize();
        if (!file.startsWith(publishRoot) || !Files.isRegularFile(file)) {
            text(exchange, 404, "Not found", "text/plain; charset=utf-8"); return;
        }
        String type = Files.probeContentType(file);
        if (type == null) type = file.toString().endsWith(".js") ? "application/javascript; charset=utf-8"
            : file.toString().endsWith(".css") ? "text/css; charset=utf-8" : "application/octet-stream";
        byte[] data = Files.readAllBytes(file);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", type); headers.set("Cache-Control", "no-store");
        headers.set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, head ? -1 : data.length);
        if (!head) exchange.getResponseBody().write(data); exchange.close();
    }

    private static boolean rawGid(Map<String, String> form) { return "16".equals(form.get("gid")); }
    private static boolean post(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) { empty(exchange, 204); return false; }
        return method(exchange, "POST");
    }
    private static boolean method(HttpExchange exchange, String expected) throws IOException {
        if (exchange.getRequestMethod().equalsIgnoreCase(expected)) return true;
        exchange.getResponseHeaders().set("Allow", expected); json(exchange, 405, error("method not allowed")); return false;
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
            values.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
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
        Headers headers = exchange.getResponseHeaders(); headers.set("Content-Type", type);
        headers.set("Cache-Control", "no-store"); headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Headers", "Content-Type, Authorization, game-id, web-token, Idempotency-Key, X-Session-Token");
        headers.set("Access-Control-Allow-Methods", "GET, POST, HEAD, OPTIONS");
        exchange.sendResponseHeaders(status, data.length); exchange.getResponseBody().write(data); exchange.close();
    }
    private static void empty(HttpExchange exchange, int status) throws IOException {
        Headers headers = exchange.getResponseHeaders(); headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Headers", "Content-Type, Authorization, game-id, web-token, Idempotency-Key, X-Session-Token");
        headers.set("Access-Control-Allow-Methods", "GET, POST, HEAD, OPTIONS");
        exchange.sendResponseHeaders(status, -1); exchange.close();
    }
    private static String ok(String data) { return "{\"code\":200,\"data\":" + data + ",\"info\":\"ok\"}"; }
    private static String error(String message) {
        return "{\"code\":400,\"data\":{},\"info\":" + Json.quote(message == null ? "error" : message) + "}";
    }
    private static long cents(BigDecimal value) { return value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact(); }
    private static String moneyCents(long cents) { return BigDecimal.valueOf(cents, 2).setScale(2).toPlainString(); }
    private static String number(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    private static String money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP).toPlainString(); }
    private static int positiveInt(String value, int fallback) {
        try { return Math.max(1, Integer.parseInt(value)); } catch (NumberFormatException ignored) { return fallback; }
    }
    private static Properties load(Path path) throws IOException {
        Properties result = new Properties(); try (InputStream input = Files.newInputStream(path)) { result.load(input); }
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

    private static final class Session {
        private long balanceCents;
        private ActiveRound active;
        private final Map<String, String> replays = new LinkedHashMap<>();
        private Session(long balanceCents) { this.balanceCents = balanceCents; }
    }

    private static final class ActiveRound {
        private final CompleteRound round;
        private final String transferId;
        private final long createdAt;
        private final long balanceAfterBet;
        private final int deliveryIndex;
        private final BigDecimal freePayout;
        private final List<String> emittedSteps;
        private ActiveRound(CompleteRound round, String transferId, long createdAt, long balanceAfterBet,
                            int deliveryIndex, BigDecimal freePayout, List<String> emittedSteps) {
            this.round = round; this.transferId = transferId; this.createdAt = createdAt;
            this.balanceAfterBet = balanceAfterBet; this.deliveryIndex = deliveryIndex;
            this.freePayout = freePayout; this.emittedSteps = emittedSteps;
        }
    }

    private record History(String transferId, long createdAt, long balanceAfterBet,
                           CompleteRound round, List<String> steps) {
        String rowJson() {
            return "{\"bet_amount\":" + Json.quote(money(round.paidBet()))
                + ",\"bid\":" + Json.quote("16-" + transferId) + ",\"created_at\":" + createdAt
                + ",\"game_type\":16,\"tis\":" + Json.quote(transferId)
                + ",\"transfer_id\":" + transferId + ",\"win_amount\":" + Json.quote(money(round.payout())) + "}";
        }
        String detailJson() {
            List<String> base = new ArrayList<>();
            List<String> free = new ArrayList<>();
            for (int i = 0; i < round.steps().size(); i++) {
                Step step = round.steps().get(i);
                String historyStep = historyStepJson(step, transferId, createdAt);
                (step.smallGameType() == 2 && step.nowFreeSpinCount() > 0 ? free : base).add(historyStep);
            }
            return "{\"balance_after\":" + Json.quote(moneyCents(balanceAfterBet))
                + ",\"base_spin_list\":[" + String.join(",", base) + "]"
                + (free.isEmpty() ? "" : ",\"free_spin_list\":[" + String.join(",", free) + "]")
                + ",\"bid\":" + Json.quote("16-" + transferId) + "}";
        }

        private static String historyStepJson(Step step, String transferId, long createdAt) {
            String matches = "[" + String.join(",", step.winMatches().stream().map(match ->
                "{\"symbol_key\":" + Json.quote(match.symbolKey())
                + ",\"win_match_key\":[" + String.join(",", match.indices().stream().map(String::valueOf).toList())
                + "],\"win_amount\":" + Json.quote(money(match.winAmount())) + "}").toList()) + "]";
            return "{\"bid\":" + Json.quote("16-" + transferId)
                + ",\"created_at\":" + createdAt
                + ",\"bet_amount\":" + Json.quote(money(step.betAmount()))
                + ",\"bet_size\":" + Json.quote(money(step.betSize()))
                + ",\"bet_level\":" + step.betLevel()
                + ",\"win_amount\":" + Json.quote(money(step.winAmount()))
                + ",\"round_win_amount\":" + Json.quote(money(step.roundWinAmount()))
                + ",\"spin_status\":" + step.spinStatus()
                + ",\"free_spin_num\":" + step.freeSpinNum()
                + ",\"now_free_spin_count\":" + step.nowFreeSpinCount()
                + ",\"game_type\":" + (step.nowFreeSpinCount() > 0 ? 2 : 1)
                + ",\"small_game_type\":" + step.smallGameType()
                + ",\"rand_symbol_key_list\":" + Json.strings(step.symbols())
                + ",\"win_x_key_list\":" + Json.strings(step.winXKeys())
                + ",\"win_match_key_list\":" + matches + "}";
        }
    }

    private record RedisConfig(String host, int port, int database, String password, int timeoutMillis) {
        static RedisConfig from(Properties config) {
            return new RedisConfig(config.getProperty("redis.host", "18.234.101.161"),
                Integer.parseInt(config.getProperty("redis.port", "8021")),
                Integer.parseInt(config.getProperty("redis.database", "0")),
                config.getProperty("redis.password", ""),
                Integer.parseInt(config.getProperty("redis.timeout-millis", "300")));
        }
    }

    static final class Json {
        private Json() { }
        static String quote(String value) {
            StringBuilder result = new StringBuilder("\"");
            for (char c : value.toCharArray()) switch (c) {
                case '\\' -> result.append("\\\\"); case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n"); case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t"); default -> result.append(c);
            }
            return result.append('"').toString();
        }
        static String strings(List<String> values) { return "[" + String.join(",", values.stream().map(Json::quote).toList()) + "]"; }
    }
}
