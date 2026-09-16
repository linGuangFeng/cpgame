package com.cpgame.replica.crazygems;

import com.cpgame.demo.redis.RedisFloorLookup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hd.pg.appapi.business.vo.cpgame.crazygems.CrazyGemsBoard;
import com.hd.pg.appapi.business.vo.cpgame.crazygems.CrazyGemsEvaluation;
import com.hd.pg.appapi.business.vo.cpgame.crazygems.CrazyGemsIndependentLossGenerator;
import com.hd.pg.appapi.business.vo.cpgame.crazygems.CrazyGemsResultUtil;
import com.hd.pg.appapi.business.vo.cpgame.crazygems.CrazyGemsRulesMetadata;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.URI;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Serves the original static package and draws complete rounds from Redis db=15.
 * Never deals a board locally.
 */
public final class CrazyGemsController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, SessionState> SESSIONS = new ConcurrentHashMap<>();
    private static final BigDecimal[] BET_SIZES = {
            new BigDecimal("0.5"), new BigDecimal("5"), new BigDecimal("50")
    };
    private static final String PLAY_PREFIX = "/play/58-Crazy-Gems";
    private static final String GAME_URL_INJECT =
            "<script>window.GameUrl=location.pathname.indexOf(\"/play/58-Crazy-Gems\")===0?location.origin+\"/play/58-Crazy-Gems\":location.origin;</script>";

    private final Path publishRoot;
    private final BigDecimal initialBalance;
    private final RedisPool redis;
    private final double lossProbability;
    private final CrazyGemsIndependentLossGenerator lossGenerator = new CrazyGemsIndependentLossGenerator();

    private CrazyGemsController(Path publishRoot, Properties config, RedisPool redis) {
        this.publishRoot = publishRoot.toAbsolutePath().normalize();
        this.initialBalance = new BigDecimal(config.getProperty("session.initial-balance", "10000.00"))
                .setScale(2, RoundingMode.HALF_UP);
        this.redis = redis;
        this.lossProbability = Double.parseDouble(config.getProperty("round.loss-probability", "0.8267"));
        if (lossProbability < 0 || lossProbability > 1) {
            throw new IllegalArgumentException("round.loss-probability must be 0..1");
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        int port = requiredPort(options);
        Properties config = loadConfig(options.get("config"));
        Path publish = resolvePublish(options.getOrDefault("publish",
                config.getProperty("publish.directory", "")));
        RedisPool redis = RedisPool.create(config);
        CrazyGemsController controller = new CrazyGemsController(publish, config, redis);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", controller::handle);
        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            try { redis.close(); } catch (IOException ignored) { }
        }, "crazy-gems-controller-stop"));
        server.start();
        System.out.printf("CONTROLLER_READY gameId=58 port=%d pid=%d rulesVersion=%s rulesHash=%s publish=%s redis=%s:%s db=%s%n",
                port, ProcessHandle.current().pid(), CrazyGemsRulesMetadata.VERSION, CrazyGemsRulesMetadata.HASH,
                publish, config.getProperty("redis.host", "18.234.101.161"),
                config.getProperty("redis.port", "8021"),
                config.getProperty("redis.database", "0"));
        try {
            redis.ensure();
        } catch (Exception ex) {
            System.err.println("[WARN] Redis not ready at startup: " + ex.getMessage()
                    + " — HTTP is up; spin will retry Redis.");
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendEmpty(exchange, 204);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("HEAD".equalsIgnoreCase(method) || "GET".equalsIgnoreCase(method)) {
                if ("/".equals(path) || path.isEmpty()) {
                    redirect(exchange, PLAY_PREFIX + "/index.html?gid=58&l=pt&t=demo");
                    return;
                }
                serveStatic(exchange);
                return;
            }
            if (!"POST".equalsIgnoreCase(method)) {
                sendError(exchange, 405, "method not allowed");
                return;
            }
            Map<String, String> form = form(exchange);
            SessionState session = session(form);
            if (path.startsWith(PLAY_PREFIX)) path = path.substring(PLAY_PREFIX.length());
            if (path.isEmpty()) path = "/";
            String api = stripCp(path);
            switch (api) {
                case "/api/v1/auth/verify", "/api/v1/auth/session" -> sendJson(exchange, envelope(verifyData(session)));
                case "/api/v1/crazy-gems/config" -> sendJson(exchange, envelope(configData()));
                case "/api/v1/crazy-gems/spin" -> sendJson(exchange, envelope(spinData(session, form)));
                case "/api/v1/crazy-gems/log-list" -> sendJson(exchange, envelope(historyList(session, form)));
                case "/api/v1/crazy-gems/log-view" -> sendJson(exchange, envelope(historyView(session, form)));
                case "/api/v1/ping" -> sendJson(exchange, envelope(JSON.createObjectNode()));
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

    private ObjectNode verifyData(SessionState session) {
        ObjectNode data = JSON.createObjectNode();
        ObjectNode player = data.putObject("player");
        player.put("id", session.playerId);
        player.put("balance", money(session.balance));
        player.put("fbt", 0);
        data.put("token", session.token);
        ObjectNode ping = data.putObject("ping");
        ping.put("enable", true);
        ping.put("seconds", 10);
        return data;
    }

    private ObjectNode configData() {
        ObjectNode data = JSON.createObjectNode();
        ArrayNode auto = data.putArray("auto");
        for (int value : new int[]{10, 30, 50, 100, 500}) auto.add(value);
        ArrayNode bll = data.putArray("bll");
        for (int i = 1; i <= 10; i++) bll.add(i);
        ArrayNode bsl = data.putArray("bsl");
        bsl.add(0.5).add(5).add(50);
        data.put("cc", "BRL");
        data.put("cs", "R$");
        data.put("dbl", 50);
        data.put("dbs", 0.5);
        ObjectNode spl = data.putObject("spl");
        CrazyGemsResultUtil.PAY.forEach((symbol, pay) -> putNumber(spl, symbol, pay));
        data.put("ts", Instant.now().getEpochSecond());
        return data;
    }

    private ObjectNode spinData(SessionState session, Map<String, String> form) throws IOException {
        synchronized (session) {
            int bl = integer(form.getOrDefault("bl", "1"), "bl");
            BigDecimal bs = decimal(form.getOrDefault("bs", "0.5"), "bs");
            if (bl < 1 || bl > 10) throw new IllegalArgumentException("bl out of range");
            if (!legalBetSize(bs)) throw new IllegalArgumentException("bs out of range");
            BigDecimal ba = bs.multiply(BigDecimal.valueOf(bl)).setScale(2, RoundingMode.HALF_UP);
            if (session.balance.compareTo(ba) < 0) throw new IllegalArgumentException("insufficient balance");
            CrazyGemsBoard board;
            CrazyGemsEvaluation evaluation;
            if (RANDOM.nextDouble() < lossProbability) {
                board = lossGenerator.generate(RANDOM);
                evaluation = CrazyGemsResultUtil.evaluate(board);
                if (!evaluation.loss()) {
                    throw new IllegalStateException("independent loss generator returned a win");
                }
            } else {
                DrawnRound drawn = redis.take(RANDOM);
                board = drawn.fact().board();
                evaluation = CrazyGemsResultUtil.evaluate(board);
                if (evaluation.multiplierDeci() != drawn.ratio() || evaluation.loss()) {
                    throw new IllegalStateException("cached member failed ResultUtil");
                }
            }
            BigDecimal wa = CrazyGemsResultUtil.winAmount(evaluation, bs, bl);
            session.balance = session.balance.subtract(ba).add(wa).setScale(2, RoundingMode.HALF_UP);
            long tis = nextTransferId();
            HistoryRow row = new HistoryRow(tis, ba, bs, bl, session.balance, wa, board, evaluation,
                    Instant.now().getEpochSecond());
            session.history.add(0, row);
            ObjectNode data = JSON.createObjectNode();
            putNumber(data, "ba", ba);
            data.put("pb", money(session.balance));
            data.put("rpx", board.rpx());
            ArrayNode rskl = data.putArray("rskl");
            for (String symbol : board.rskl()) rskl.add(symbol);
            putNumber(data, "wa", wa);
            ObjectNode wmkl = data.putObject("wmkl");
            evaluation.wmkl().forEach(wmkl::put);
            return data;
        }
    }

    private ObjectNode historyList(SessionState session, Map<String, String> form) {
        int page = Math.max(1, integer(form.getOrDefault("page_index", "1"), "page_index"));
        int pageSize = 10;
        synchronized (session) {
            int from = (page - 1) * pageSize;
            int to = Math.min(session.history.size(), from + pageSize);
            ObjectNode data = JSON.createObjectNode();
            BigDecimal totalBet = BigDecimal.ZERO;
            BigDecimal totalWin = BigDecimal.ZERO;
            for (HistoryRow row : session.history) {
                totalBet = totalBet.add(row.ba);
                totalWin = totalWin.add(row.wa);
            }
            data.put("ba", money(totalBet));
            data.put("wa", money(totalWin));
            data.put("lc", session.history.size());
            data.put("end", to >= session.history.size() ? 1 : 0);
            ArrayNode list = data.putArray("ll");
            if (from < session.history.size()) {
                for (HistoryRow row : session.history.subList(from, to)) {
                    ObjectNode item = list.addObject();
                    item.put("ba", money(row.ba));
                    item.put("baf", money(row.baf));
                    item.put("bid", "58-" + row.tis);
                    item.put("ca", row.ca);
                    item.put("fe", 0);
                    item.putNull("gm");
                    item.put("gt", 58);
                    item.put("tis", Long.toString(row.tis));
                    item.put("wa", money(row.wa));
                }
            }
            return data;
        }
    }

    private ObjectNode historyView(SessionState session, Map<String, String> form) {
        String transfer = form.getOrDefault("transfer_id", "");
        synchronized (session) {
            HistoryRow row = session.history.stream()
                    .filter(item -> Long.toString(item.tis).equals(transfer) || ("58-" + item.tis).equals(transfer))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown transfer_id"));
            ObjectNode data = JSON.createObjectNode();
            putNumber(data, "ba", row.ba);
            data.put("baf", money(row.baf));
            data.put("bid", "58-" + row.tis);
            data.put("bl", row.bl);
            putNumber(data, "bs", row.bs);
            data.put("ca", row.ca);
            data.put("gt", 1);
            data.put("pb", money(row.baf));
            data.put("rpx", row.board.rpx());
            ArrayNode rskl = data.putArray("rskl");
            for (String symbol : row.board.rskl()) rskl.add(symbol);
            putNumber(data, "wa", row.wa);
            ObjectNode wmkl = data.putObject("wmkl");
            row.evaluation.wmkl().forEach(wmkl::put);
            return data;
        }
    }

    private void serveStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.contains("..")) {
            sendError(exchange, 400, "bad path");
            return;
        }
        if (path.equals(PLAY_PREFIX) || path.equals(PLAY_PREFIX + "/")) {
            path = PLAY_PREFIX + "/index.html";
        }
        if (path.startsWith(PLAY_PREFIX + "/")) {
            path = path.substring(PLAY_PREFIX.length());
        }
        String relative = path.startsWith("/") ? path.substring(1) : path;
        if (relative.isEmpty()) relative = "index.html";
        Path file = publishRoot.resolve(relative).normalize();
        if (!file.startsWith(publishRoot)) {
            sendError(exchange, 400, "bad path");
            return;
        }
        if (Files.isDirectory(file)) file = file.resolve("index.html");
        if (!Files.isRegularFile(file)) {
            sendError(exchange, 404, "not found: " + path);
            return;
        }
        byte[] body = Files.readAllBytes(file);
        String name = file.getFileName().toString();
        if (name.equals("index.html")) {
            String html = new String(body, StandardCharsets.UTF_8);
            if (!html.contains("window.GameUrl")) {
                html = html.replaceFirst("<head>", "<head>" + GAME_URL_INJECT);
            }
            body = html.getBytes(StandardCharsets.UTF_8);
        }
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType(name));
        headers.set("Cache-Control", name.endsWith(".html") ? "no-store" : "public, max-age=3600");
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, body.length);
            return;
        }
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(body); }
    }

    private SessionState session(Map<String, String> form) {
        String token = firstNonBlank(form.get("t"), form.get("token"), "demo");
        return SESSIONS.computeIfAbsent(token, key -> new SessionState(key, initialBalance));
    }

    private static String stripCp(String path) {
        return path.startsWith("/cp/") ? path.substring(3) : path;
    }

    private static boolean legalBetSize(BigDecimal bs) {
        for (BigDecimal allowed : BET_SIZES) if (allowed.compareTo(bs) == 0) return true;
        return false;
    }

    private static long nextTransferId() {
        return (Instant.now().toEpochMilli() << 20) | (RANDOM.nextInt(1 << 20));
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static void putNumber(ObjectNode node, String field, BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() <= 0) node.put(field, stripped.longValueExact());
        else node.put(field, stripped);
    }

    private static int integer(String raw, String name) {
        try { return Integer.parseInt(raw.trim()); }
        catch (Exception ex) { throw new IllegalArgumentException("invalid " + name); }
    }

    private static BigDecimal decimal(String raw, String name) {
        try { return new BigDecimal(raw.trim()); }
        catch (Exception ex) { throw new IllegalArgumentException("invalid " + name); }
    }

    private static ObjectNode envelope(ObjectNode data) {
        ObjectNode root = JSON.createObjectNode();
        root.put("code", 200);
        root.set("data", data);
        root.put("info", "ok");
        return root;
    }

    private static void sendJson(HttpExchange exchange, ObjectNode body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("code", status);
        root.put("info", message == null ? "" : message);
        root.set("data", JSON.createObjectNode());
        byte[] bytes = JSON.writeValueAsBytes(root);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static void sendEmpty(HttpExchange exchange, int status) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Headers", "*");
        headers.set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        exchange.sendResponseHeaders(status, -1);
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    private static String contentType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wasm")) return "application/wasm";
        return "application/octet-stream";
    }

    private static Map<String, String> form(HttpExchange exchange) throws IOException {
        byte[] bytes;
        try (InputStream input = exchange.getRequestBody()) { bytes = input.readAllBytes(); }
        String raw = new String(bytes, StandardCharsets.UTF_8);
        Map<String, String> values = new LinkedHashMap<>();
        if (!raw.isBlank()) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                if (eq < 0) values.put(decode(pair), "");
                else values.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        URI uri = exchange.getRequestURI();
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                int eq = pair.indexOf('=');
                if (eq < 0) values.putIfAbsent(decode(pair), "");
                else values.putIfAbsent(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value.replace("+", "%20"), StandardCharsets.UTF_8);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) continue;
            String key = arg.substring(2);
            String value = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true";
            values.put(key, value);
        }
        return values;
    }

    private static int requiredPort(Map<String, String> options) {
        String raw = options.get("port");
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("missing --port");
        int port = Integer.parseInt(raw);
        if (port < 50000 || port > 59999) throw new IllegalArgumentException("port must be 50000-59999");
        return port;
    }

    private static Properties loadConfig(String path) throws IOException {
        Properties properties = new Properties();
        if (path == null || path.isBlank()) return properties;
        Path file = Path.of(path).toAbsolutePath().normalize();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        return properties;
    }

    private static Path resolvePublish(String raw) {
        Path publish = Path.of(raw == null || raw.isBlank()
                ? "../../../publish/58-Crazy-Gems" : raw).toAbsolutePath().normalize();
        if (!Files.isDirectory(publish)) throw new IllegalArgumentException("publish directory not found: " + publish);
        if (!Files.isRegularFile(publish.resolve("index.html"))) {
            throw new IllegalArgumentException("publish/index.html not found: " + publish);
        }
        if (!Files.isRegularFile(publish.resolve("src/settings.78db3.js"))) {
            throw new IllegalArgumentException("main settings js missing");
        }
        if (!Files.isRegularFile(publish.resolve("asset/cocos2d-js-min.55e56.js"))) {
            throw new IllegalArgumentException("cocos js missing");
        }
        return publish;
    }

    private static final class SessionState {
        final String token;
        final long playerId;
        BigDecimal balance;
        final List<HistoryRow> history = new ArrayList<>();

        SessionState(String token, BigDecimal balance) {
            this.token = token;
            this.playerId = 10000 + Math.floorMod(token.hashCode(), 90000);
            this.balance = balance;
        }
    }

    private record HistoryRow(long tis, BigDecimal ba, BigDecimal bs, int bl, BigDecimal baf, BigDecimal wa,
                              CrazyGemsBoard board, CrazyGemsEvaluation evaluation, long ca) { }

    record DrawnRound(CompleteRoundFact fact, int ratio) { }

    static final class RedisPool implements AutoCloseable {
        private final Properties config;
        private final long gameId;
        private final Object lock = new Object();
        private volatile RedisConnection redis;

        private RedisPool(Properties config, long gameId) {
            this.config = config;
            this.gameId = gameId;
        }

        static RedisPool create(Properties config) {
            long gameId = Long.parseLong(config.getProperty("redis.game-id", "8000058"));
            return new RedisPool(config, gameId);
        }

        void ensure() throws IOException {
            if (redis != null) return;
            synchronized (lock) {
                if (redis != null) return;
                int port = Integer.parseInt(config.getProperty("redis.port", "8021"));
                int database = Integer.parseInt(config.getProperty("redis.database", "0"));
                String username = config.getProperty("redis.username", "");
                String password = config.getProperty("redis.password", "");
                boolean ssl = Boolean.parseBoolean(config.getProperty("redis.ssl", "false"));
                int connectMs = Integer.parseInt(config.getProperty("redis.connect-timeout-ms", "5000"));
                int socketMs = Integer.parseInt(config.getProperty("redis.socket-timeout-ms", "30000"));
                IOException last = null;
                for (String host : redisHosts()) {
                    try {
                        redis = RedisConnection.connect(host, port, username, password, database, ssl,
                                connectMs, socketMs);
                        return;
                    } catch (IOException ex) {
                        last = ex;
                        System.err.println("[WARN] Redis " + host + ":" + port + " " + ex.getMessage());
                    }
                }
                throw last != null ? last : new IOException("unable to connect Redis");
            }
        }

        private List<String> redisHosts() {
            List<String> hosts = new ArrayList<>();
            for (String host : new String[]{
                    config.getProperty("redis.host", "").trim(),
                    "18.234.101.161",
                    "127.0.0.1"
            }) {
                if (host.isEmpty() || hosts.contains(host)) continue;
                hosts.add(host);
            }
            return hosts;
        }

        DrawnRound take(SecureRandom random) throws IOException {
            ensure();
            DrawnRound drawn = takeFrom(random);
            if (drawn == null) throw new IllegalStateException("Redis round cache is empty");
            return drawn;
        }

        private DrawnRound takeFrom(SecureRandom random) throws IOException {
            var buckets = RedisFloorLookup.open(redis::command, RedisKeys.index(gameId),
                    m -> RedisKeys.list(gameId, m), random, 1, Integer.MAX_VALUE);
            Integer ratio;
            while ((ratio = buckets.next()) != null) {
                DrawnRound round = readMember(ratio, random);
                if (round != null) return round;
            }
            return null;
        }

        private long llen(int ratio) throws IOException {
            String list = RedisKeys.list(gameId, ratio);
            Object lenObj = redis.command("LLEN", list);
            return lenObj instanceof Long value ? value : Long.parseLong(lenObj.toString());
        }

        private DrawnRound readMember(int ratio, SecureRandom random) throws IOException {
            long len = llen(ratio);
            if (len <= 0) return null;
            int offset = random.nextInt((int) Math.min(len, Integer.MAX_VALUE));
            String list = RedisKeys.list(gameId, ratio);
            Object member = redis.command("LINDEX", list, Integer.toString(offset));
            if (member == null) return null;
            CompleteRoundFact fact = new CompleteRoundCodec().decode(member.toString());
            return new DrawnRound(fact, ratio);
        }

        @SuppressWarnings("unchecked")
        private static List<Object> asList(Object value) {
            if (value == null) return List.of();
            if (value instanceof List<?> list) return (List<Object>) list;
            return List.of();
        }

        @Override
        public void close() throws IOException {
            RedisConnection connection = redis;
            if (connection != null) connection.close();
        }
    }
}
