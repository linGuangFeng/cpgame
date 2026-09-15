package com.cpgame.jungletreasure.api;

import com.cpgame.batcha.g32.CompleteRound;
import com.cpgame.batcha.g32.GameRuleCore;
import com.cpgame.batcha.g32.IndependentVerifier;
import com.cpgame.batcha.g32.MemberCodec;
import com.cpgame.batcha.g32.RedisRespRoundStore;
import com.cpgame.batcha.g32.RedisRoundStore;
import com.cpgame.batcha.g32.RedisRoundWriter;
import com.cpgame.batcha.g32.RoundMode;
import com.cpgame.batcha.g32.Step;
import com.cpgame.batcha.g32.WinMatch;
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

public final class ServerMain {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final MemberCodec CODEC = new MemberCodec();
    private static final IndependentVerifier VERIFIER = new IndependentVerifier(new BigDecimal("20000"), 12, 10);
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
        int rawGameId = Integer.parseInt(config.getProperty("game.raw-id", "32"));
        if (rawGameId != GameRuleCore.RAW_GAME_ID) throw new IllegalArgumentException("game.raw-id 必须为 raw gid 32");
        redis = RedisConfig.from(config);
        initialBalanceCents = Long.parseLong(config.getProperty("runtime.initial-balance-cents", "100000000"));
        String host = option(args, "--server.address", config.getProperty("server.host", "0.0.0.0"));
        String portText = option(args, "--port", System.getenv("PORT"));
        if (portText == null || portText.isBlank()) {
            throw new IllegalArgumentException("contract v3 controller requires --port or PORT");
        }
        int port = Integer.parseInt(portText);
        if (port < 50000 || port > 59999) {
            throw new IllegalArgumentException("managed port must be in 50000-59999");
        }
        Path configuredPublish = Path.of(config.getProperty("publish.root", "../../../publish/32-Jungle-Treasure"));
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
        server.createContext("/cp/api/v1/jungle-treasure/config", ServerMain::config);
        server.createContext("/cp/api/v1/jungle-treasure/spin", ServerMain::spin);
        server.createContext("/cp/api/v1/jungle-treasure/log-list", ServerMain::historyList);
        server.createContext("/cp/api/v1/jungle-treasure/log-view", ServerMain::historyView);
        server.createContext("/Init", ServerMain::verify);
        server.createContext("/Config", ServerMain::config);
        server.createContext("/Spin", ServerMain::spin);
        server.createContext("/History", ServerMain::historyList);
        server.createContext("/", ServerMain::staticFile);
        server.setExecutor(Executors.newFixedThreadPool(12));
        server.start();
        System.out.println("CONTROLLER_READY rawGameId=32 contractVersion=3 port=" + port
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
        json(exchange, 200, "{\"status\":\"UP\",\"rawGameId\":32,\"contractVersion\":3,\"processMode\":\"managed-process\",\"rulesHash\":"
            + Json.quote(GameRuleCore.RULES_HASH) + ",\"redisReachable\":" + redisReachable
            + ",\"redisPools\":" + pools + ",\"activeSessions\":" + SESSIONS.size() + "}");
    }

    private static void verify(HttpExchange exchange) throws IOException {
        if (!post(exchange)) return;
        Map<String, String> form = form(exchange);
        if (!rawGid(form)) { json(exchange, 400, error("gid 必须为 raw gid 32")); return; }
        String token = "local-gid32-" + UUID.randomUUID();
        Session session = new Session(initialBalanceCents);
        SESSIONS.put(token, session);
        String data = "{\"player\":{\"balance\":" + Json.quote(moneyCents(session.balanceCents))
            + ",\"id\":32},\"token\":" + Json.quote(token) + ",\"t\":" + Json.quote(token)
            + ",\"ping\":{\"enable\":0,\"seconds\":60},\"rc\":{\"on\":0,\"v\":2},\"gc\":{\"os\":1,\"om\":1}}";
        json(exchange, 200, ok(data));
    }

    private static void config(HttpExchange exchange) throws IOException {
        if (!post(exchange)) return;
        Map<String, String> form = form(exchange);
        if (!rawGid(form)) { json(exchange, 400, error("gid 必须为 raw gid 32")); return; }
        Session session = sessionOf(form);
        String last = "null";
        synchronized (session) {
            if (session.last != null) last = lastJson(session.last);
        }
        String data = "{\"auto\":[10,30,50,100,500],\"bll\":[1,2,3,4,5,6,7,8,9,10],"
            + "\"bsl\":[0.02,0.1,0.2],\"cc\":\"BRL\",\"cs\":\"R$\",\"ct\":\".\","
            + "\"dbl\":10,\"dbs\":0.02,\"last\":" + last + ",\"spl\":" + paytableJson()
            + ",\"ts\":" + Instant.now().getEpochSecond() + "}";
        json(exchange, 200, ok(data));
    }

    private static void spin(HttpExchange exchange) throws IOException {
        if (!post(exchange)) return;
        Map<String, String> form = form(exchange);
        if (!rawGid(form)) { json(exchange, 400, error("gid 必须为 raw gid 32")); return; }
        Session session = sessionOf(form);
        String requestId = form.getOrDefault("request_id", "").strip();
        if (requestId.isEmpty()) requestId = UUID.randomUUID().toString();
        try {
            String response;
            synchronized (session) {
                response = session.replays.get(requestId);
                if (response == null) {
                    int betLevel = Integer.parseInt(form.getOrDefault("bet_level", form.getOrDefault("bl", "10")));
                    BigDecimal betSize = new BigDecimal(form.getOrDefault("bet_size", form.getOrDefault("bs", "0.02")));
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

    private static Session sessionOf(Map<String, String> form) {
        String token = form.getOrDefault("t", "").strip();
        return SESSIONS.computeIfAbsent(token.isEmpty() ? "local-anonymous" : token,
            ignored -> new Session(initialBalanceCents));
    }

    private static void beginRound(Session session, BigDecimal betSize, int betLevel, RoundMode requestedMode) {
        BigDecimal requestedBet = GameRuleCore.paidBet(betSize, betLevel);
        long requestedBetCents = cents(requestedBet);
        if (session.balanceCents < requestedBetCents) throw new IllegalArgumentException("余额不足");
        CompleteRound member = claimFormalRound(requestedMode);
        List<Step> facts = member.steps().stream().map(step -> Step.fact(
            step.deliveryIndex(), step.deliveryIndex() == 0 ? requestedBet : BigDecimal.ZERO,
            betSize, betLevel, step.tokens(), step.silver(), step.gold())).toList();
        CompleteRound round = GameRuleCore.materialize(requestedBet, betSize, betLevel, facts);
        VERIFIER.verify(round);
        session.balanceCents -= cents(round.paidBet());
        String transferId = Long.toString(Instant.now().toEpochMilli() * 1000L
            + Math.floorMod(TRANSFER_SEQUENCE.incrementAndGet(), 1000L));
        session.active = new ActiveRound(round, transferId, Instant.now().getEpochSecond(),
            session.balanceCents, 0);
    }

    private static CompleteRound claimFormalRound(RoundMode requestedMode) {
        try (RedisRoundStore store = openRedisStore()) {
            RedisRoundWriter writer = new RedisRoundWriter(store, CODEC, VERIFIER, 300);
            List<RoundMode> modes;
            if (requestedMode != null) modes = List.of(requestedMode);
            else modes = RANDOM.nextBoolean()
                ? List.of(RoundMode.WIN, RoundMode.FREE)
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
            case "WIN", "ORDINARY_WIN" -> RoundMode.WIN;
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
        int next = active.deliveryIndex + 1;
        boolean complete = next >= active.round.steps().size();
        if (complete) session.balanceCents += cents(active.round.payout());
        String stepJson = stepJson(step, session.balanceCents, complete ? 0 : Math.max(0,
            step.freeSpinNum() - step.nowFreeSpinCount()));
        session.last = step;
        if (complete) {
            History history = new History(active.transferId, active.createdAt, active.balanceAfterBet, active.round);
            HISTORY.put(active.transferId, history);
            synchronized (HISTORY_ORDER) { HISTORY_ORDER.add(0, active.transferId); }
            session.active = null;
        } else {
            session.active = new ActiveRound(active.round, active.transferId, active.createdAt,
                active.balanceAfterBet, next);
        }
        return ok(stepJson);
    }

    private static void historyList(HttpExchange exchange) throws IOException {
        if (!post(exchange)) return;
        Map<String, String> form = form(exchange);
        if (!rawGid(form)) { json(exchange, 400, error("gid 必须为 raw gid 32")); return; }
        int page = positiveInt(form.getOrDefault("page_index", "1"), 1);
        List<String> ids;
        synchronized (HISTORY_ORDER) { ids = List.copyOf(HISTORY_ORDER); }
        int start = Math.min(ids.size(), (page - 1) * 10);
        int end = Math.min(ids.size(), start + 10);
        List<History> pageRows = ids.subList(start, end).stream().map(HISTORY::get).toList();
        BigDecimal totalBet = pageRows.stream().map(row -> row.round.paidBet()).reduce(BigDecimal.ZERO, BigDecimal::add);
        String data = "{\"ba\":" + Json.quote(money(totalBet)) + ",\"end\":" + (end >= ids.size() ? 1 : 0)
            + ",\"lc\":" + ids.size() + ",\"ll\":[" + String.join(",", pageRows.stream().map(History::rowJson).toList()) + "]}";
        json(exchange, 200, ok(data));
    }

    private static void historyView(HttpExchange exchange) throws IOException {
        if (!post(exchange)) return;
        Map<String, String> form = form(exchange);
        if (!rawGid(form)) { json(exchange, 400, error("gid 必须为 raw gid 32")); return; }
        History history = HISTORY.get(form.getOrDefault("transfer_id", form.getOrDefault("tis", "")));
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
            json(exchange, 200, ok("{\"rawGameId\":32,\"balance\":"
                + Json.quote(moneyCents(session.balanceCents)) + ",\"activeRound\":" + active + "}"));
        }
    }

    private static void timingReport(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        empty(exchange, 204);
    }

    private static String stepJson(Step step, long balanceCents, int fbt) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"ba\":").append(number(step.betAmount()))
            .append(",\"frwa\":").append(number(step.freeRoundWinAmount()))
            .append(",\"fsn\":").append(step.freeSpinNum())
            .append(",\"gfl\":").append(Json.ints(step.gold()))
            .append(",\"gt\":").append(step.gameType())
            .append(",\"nfsc\":").append(step.nowFreeSpinCount())
            .append(",\"pl\":{\"balance\":").append(Json.quote(moneyCents(balanceCents))).append(",\"id\":32}")
            .append(",\"rpx\":").append(step.roundPayX())
            .append(",\"rskl\":").append(Json.strings(step.tokens()))
            .append(",\"rwa\":").append(number(step.roundWinAmount()))
            .append(",\"sfl\":").append(Json.ints(step.silver()))
            .append(",\"small_game_type\":").append(step.smallGameType())
            .append(",\"ss\":").append(step.spinStatus())
            .append(",\"wa\":").append(number(step.winAmount()))
            .append(",\"wmkl\":").append(wmkl(step.winMatches()))
            .append(",\"wskl\":").append(Json.strings(step.winSymbolKeys()))
            .append(",\"fbt\":").append(fbt)
            .append('}');
        return json.toString();
    }

    private static String lastJson(Step step) {
        return "{\"ba\":" + number(step.betAmount())
            + ",\"bl\":" + step.betLevel()
            + ",\"bs\":" + number(step.betSize())
            + ",\"ca\":" + Instant.now().getEpochSecond()
            + ",\"frwa\":" + number(step.freeRoundWinAmount())
            + ",\"fsn\":" + step.freeSpinNum()
            + ",\"gfl\":" + Json.ints(step.gold())
            + ",\"gt\":" + step.gameType()
            + ",\"nfsc\":" + step.nowFreeSpinCount()
            + ",\"rpx\":" + step.roundPayX()
            + ",\"rskl\":" + Json.strings(step.tokens())
            + ",\"rwa\":" + number(step.roundWinAmount())
            + ",\"sfl\":" + Json.ints(step.silver())
            + ",\"small_game_type\":" + step.smallGameType()
            + ",\"ss\":" + step.spinStatus()
            + ",\"wa\":" + number(step.winAmount())
            + ",\"wmkl\":" + wmklObjects(step.winMatches())
            + ",\"wskl\":" + Json.strings(step.winSymbolKeys()) + "}";
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

    private static String wmkl(List<WinMatch> matches) {
        return "[" + String.join(",", matches.stream().map(match -> "["
            + String.join(",", match.reelGroups().stream().map(Json::ints).toList()) + "]").toList()) + "]";
    }

    private static String wmklObjects(List<WinMatch> matches) {
        return "[" + String.join(",", matches.stream().map(match -> "{\"wmk\":["
            + String.join(",", match.reelGroups().stream().map(Json::ints).toList())
            + "]}").toList()) + "]";
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

    private static boolean rawGid(Map<String, String> form) { return "32".equals(form.get("gid")); }
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
        private Step last;
        private final Map<String, String> replays = new LinkedHashMap<>();
        private Session(long balanceCents) { this.balanceCents = balanceCents; }
    }

    private static final class ActiveRound {
        private final CompleteRound round;
        private final String transferId;
        private final long createdAt;
        private final long balanceAfterBet;
        private final int deliveryIndex;
        private ActiveRound(CompleteRound round, String transferId, long createdAt, long balanceAfterBet, int deliveryIndex) {
            this.round = round; this.transferId = transferId; this.createdAt = createdAt;
            this.balanceAfterBet = balanceAfterBet; this.deliveryIndex = deliveryIndex;
        }
    }

    private record History(String transferId, long createdAt, long balanceAfterBet, CompleteRound round) {
        String rowJson() {
            return "{\"ba\":" + Json.quote(money(round.paidBet()))
                + ",\"baf\":" + Json.quote(moneyCents(balanceAfterBet))
                + ",\"bid\":" + Json.quote("32-" + transferId)
                + ",\"ca\":" + createdAt
                + ",\"fe\":0,\"gm\":null,\"gt\":32,\"tis\":" + Json.quote(transferId)
                + ",\"wa\":" + Json.quote(money(round.payout())) + "}";
        }
        String detailJson() {
            List<String> base = new ArrayList<>();
            List<String> free = new ArrayList<>();
            for (Step step : round.steps()) {
                String body = historyStep(step);
                if (step.smallGameType() == 2 && step.nowFreeSpinCount() > 0) free.add(body);
                else base.add(body);
            }
            return "{\"baf\":" + Json.quote(moneyCents(balanceAfterBet))
                + ",\"bid\":" + Json.quote("32-" + transferId)
                + ",\"bsl\":[" + String.join(",", base) + "]"
                + (free.isEmpty() ? "" : ",\"fsl\":[" + String.join(",", free) + "]") + "}";
        }
        private String historyStep(Step step) {
            return "{\"ba\":" + Json.quote(money(step.betAmount()))
                + ",\"bid\":" + Json.quote("32-" + transferId)
                + ",\"bl\":" + step.betLevel()
                + ",\"bs\":" + Json.quote(money(step.betSize()))
                + ",\"frwa\":" + Json.quote(money(step.freeRoundWinAmount()))
                + ",\"fsn\":" + step.freeSpinNum()
                + ",\"gfl\":" + Json.ints(step.gold())
                + ",\"gt\":" + step.gameType()
                + ",\"nfsc\":" + step.nowFreeSpinCount()
                + ",\"rpx\":" + step.roundPayX()
                + ",\"rskl\":" + Json.strings(step.tokens())
                + ",\"rwa\":" + Json.quote(money(step.roundWinAmount()))
                + ",\"sfl\":" + Json.ints(step.silver())
                + ",\"small_game_type\":" + step.smallGameType()
                + ",\"ss\":" + step.spinStatus()
                + ",\"wa\":" + Json.quote(money(step.winAmount()))
                + ",\"wmkl\":" + wmkl(step.winMatches())
                + ",\"wskl\":" + Json.strings(step.winSymbolKeys()) + "}";
        }
    }

    private record RedisConfig(String host, int port, int database, String password, int timeoutMillis) {
        static RedisConfig from(Properties config) {
            return new RedisConfig(config.getProperty("redis.host", "18.234.101.161"),
                Integer.parseInt(config.getProperty("redis.port", "8021")),
                Integer.parseInt(config.getProperty("redis.database", "0")),
                config.getProperty("redis.password", ""),
                Integer.parseInt(config.getProperty("redis.timeout-millis", "3000")));
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
        static String ints(List<Integer> values) { return "[" + String.join(",", values.stream().map(String::valueOf).toList()) + "]"; }
    }
}
