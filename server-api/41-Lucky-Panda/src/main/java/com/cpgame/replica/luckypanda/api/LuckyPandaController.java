package com.cpgame.replica.luckypanda.api;

import com.hd.pg.appapi.business.vo.cpgame.luckypanda.GameRuleCore;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/**
 * Controller 交付合同 v3：唯一受管 PID、平台注入 5xxxx 端口。
 * 只把 generator 里那一份 GameRuleCore 接到协议；Demo 结果只从 18.234.101.161:8021 db=15 领取完整局。
 */
public final class LuckyPandaController {
    private static final String PROVIDER_PROPERTY = "com.sun.net.httpserver.HttpServerProvider";
    private static final String REQUIRED_PROVIDER = "com.lgf.agentai.service.CpgameSharedHttpServerProvider";

    private final Path publishRoot;
    private final LuckyPandaService service;
    private final RedisRoundStore redis;

    LuckyPandaController(Path publishRoot, LuckyPandaService service, RedisRoundStore redis) {
        this.publishRoot = publishRoot == null ? null : publishRoot.toAbsolutePath().normalize();
        this.service = service;
        this.redis = redis;
        if (GameRuleCore.GAME_ID != 41) throw new IllegalStateException("gid must stay 41");
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        int port = requiredPort(options);
        Properties config = loadConfig(options.get("config"));
        rejectSeed(config);
        Path publish = resolvePublish(options.getOrDefault("publish", config.getProperty("publish.directory", "")));
        RedisRoundStore redis = RedisRoundStore.connect(config);
        BigDecimal initial = new BigDecimal(config.getProperty("session.initial-balance", "10000.00"));
        LuckyPandaService service = new LuckyPandaService(redis, initial, new SecureRandom());
        LuckyPandaController controller = new LuckyPandaController(publish, service, redis);
        HttpServer server;
        boolean shared = REQUIRED_PROVIDER.equals(System.getProperty(PROVIDER_PROPERTY, ""));
        if (shared) {
            server = HttpServer.create(InetSocketAddress.createUnresolved("shared-http", 1), 0);
        } else {
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        }
        server.createContext("/", controller::handle);
        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            try { redis.close(); } catch (IOException ignored) { }
        }, "lucky-panda-controller-stop"));
        server.start();
        System.out.printf("CONTROLLER_READY gameId=41 port=%d pid=%d rulesVersion=%s rulesHash=%s publish=%s redis=%s:%s db=%s%n",
                port, ProcessHandle.current().pid(), GameRuleCore.RULES_VERSION, GameRuleCore.RULES_HASH,
                publish, config.getProperty("redis.host", "18.234.101.161"),
                config.getProperty("redis.port", "8021"), config.getProperty("redis.database", "0"));
        if (shared) new CountDownLatch(1).await();
    }

    void handle(HttpExchange exchange) throws IOException {
        try {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendEmpty(exchange, 204);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())
                    || "HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                if ("/__controller/status".equals(path) || "/health".equals(path)) {
                    sendJson(exchange, 200, service.status());
                    return;
                }
                if ("/api/balance".equals(path) || "/cp/api/v1/lucky-panda/balance".equals(path)) {
                    sendJson(exchange, 200, service.balance(queryAndHeaders(exchange, Map.of())));
                    return;
                }
                if ("/api/session".equals(path)) {
                    sendJson(exchange, 200, service.status());
                    return;
                }
                if ("/api/report/timing".equals(path)) {
                    sendEmpty(exchange, 204);
                    return;
                }
                serveStatic(exchange);
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, LuckyPandaService.error(405, "POST required"));
                return;
            }
            Map<String, String> form = form(exchange);
            copyLogicalHeaders(exchange, form);
            switch (path) {
                case "/cp/api/v1/auth/verify", "/cp/api/v1/auth/session", "/api/v1/auth/verify",
                     "/api/v1/auth/session" ->
                        sendJson(exchange, 200, service.auth(form, launchAlias(exchange)));
                case "/cp/api/v1/lucky-panda/config", "/api/v1/lucky-panda/config" ->
                        sendJson(exchange, 200, service.config(form));
                case "/cp/api/v1/lucky-panda/spin", "/api/v1/lucky-panda/spin" ->
                        sendJson(exchange, 200, service.spin(form, exchange.getRequestHeaders().getFirst("Idempotency-Key")));
                case "/cp/api/v1/lucky-panda/log-list", "/api/v1/lucky-panda/log-list" ->
                        sendJson(exchange, 200, service.historyList(form));
                case "/cp/api/v1/lucky-panda/log-view", "/api/v1/lucky-panda/log-view" ->
                        sendJson(exchange, 200, service.historyDetail(form));
                case "/cp/api/v1/lucky-panda/balance", "/api/v1/lucky-panda/balance", "/api/balance" ->
                        sendJson(exchange, 200, service.balance(form));
                case "/cp/api/v1/ping", "/api/v1/ping" ->
                        sendJson(exchange, 200, service.ping(form));
                case "/api/report/timing" -> sendEmpty(exchange, 204);
                default -> sendJson(exchange, 404, LuckyPandaService.error(404, "unknown endpoint: " + path));
            }
        } catch (ApiException error) {
            sendJson(exchange, 200, LuckyPandaService.error(error.status, error.getMessage()));
        } catch (IllegalStateException error) {
            sendJson(exchange, 200, LuckyPandaService.error(503, error.getMessage()));
        } catch (IllegalArgumentException error) {
            sendJson(exchange, 200, LuckyPandaService.error(400, error.getMessage()));
        } catch (Exception error) {
            error.printStackTrace(System.err);
            sendJson(exchange, 200, LuckyPandaService.error(500, "controller failure"));
        } finally {
            exchange.close();
        }
    }

    private void serveStatic(HttpExchange exchange) throws IOException {
        if (publishRoot == null) {
            sendJson(exchange, 404, LuckyPandaService.error(404, "static file not found"));
            return;
        }
        URI uri = exchange.getRequestURI();
        if ("/".equals(uri.getPath()) && needsHostRewrite(uri.getRawQuery(), exchange)) {
            String location = "/?" + rewrittenLaunchQuery(uri.getRawQuery(), exchange);
            exchange.getResponseHeaders().set("Location", location);
            sendEmpty(exchange, 302);
            return;
        }
        String rawPath = uri.getPath();
        if ("/".equals(rawPath) || rawPath.isEmpty()) rawPath = "/index.html";
        Path file = publishRoot.resolve(rawPath.substring(1)).normalize();
        if (!file.startsWith(publishRoot) || !Files.isRegularFile(file)) {
            sendJson(exchange, 404, LuckyPandaService.error(404, "static file not found"));
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
        Map<String, String> q = FormCodec.parse(rawQuery == null ? "" : rawQuery);
        String expected = hostPort(exchange);
        return !expected.equalsIgnoreCase(q.getOrDefault("sip", ""))
                || !q.containsKey("t")
                || !"41".equals(q.get("gid"));
    }

    private String rewrittenLaunchQuery(String rawQuery, HttpExchange exchange) {
        Map<String, String> q = new LinkedHashMap<>(FormCodec.parse(rawQuery == null ? "" : rawQuery));
        q.putIfAbsent("ai", "luck_single_41");
        q.putIfAbsent("btt", "1");
        q.put("gid", "41");
        q.putIfAbsent("l", "en");
        q.put("sip", hostPort(exchange));
        q.putIfAbsent("t", "local-replay");
        return q.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .reduce((a, b) -> a + "&" + b)
                .orElse("gid=41");
    }

    private static Map<String, String> form(HttpExchange exchange) throws IOException {
        String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> values = new LinkedHashMap<>(FormCodec.parse(raw));
        values.putAll(FormCodec.parse(exchange.getRequestURI().getRawQuery() == null
                ? "" : exchange.getRequestURI().getRawQuery()));
        return values;
    }

    private static Map<String, String> queryAndHeaders(HttpExchange exchange, Map<String, String> form) {
        Map<String, String> values = new LinkedHashMap<>(form);
        values.putAll(FormCodec.parse(exchange.getRequestURI().getRawQuery() == null
                ? "" : exchange.getRequestURI().getRawQuery()));
        copyLogicalHeaders(exchange, values);
        return values;
    }

    private static void copyLogicalHeaders(HttpExchange exchange, Map<String, String> form) {
        String token = exchange.getRequestHeaders().getFirst("web-token");
        String gid = exchange.getRequestHeaders().getFirst("game-id");
        if (token != null && !token.isBlank()) form.putIfAbsent("t", token);
        if (gid != null && !gid.isBlank()) form.putIfAbsent("gid", gid);
    }

    private static String launchAlias(HttpExchange exchange) {
        String referer = exchange.getRequestHeaders().getFirst("Referer");
        if (referer == null) {
            return FormCodec.parse(exchange.getRequestURI().getRawQuery() == null
                    ? "" : exchange.getRequestURI().getRawQuery()).get("t");
        }
        try {
            URI uri = URI.create(referer);
            Map<String, String> query = FormCodec.parse(uri.getRawQuery() == null ? "" : uri.getRawQuery());
            String value = query.get("t");
            return value == null || value.isBlank() ? null : value;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = JsonCodec.write(value).getBytes(StandardCharsets.UTF_8);
        cors(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        send(exchange, status, body);
    }

    private static void sendEmpty(HttpExchange exchange, int status) throws IOException {
        cors(exchange);
        send(exchange, status, new byte[0]);
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        cors(exchange);
        exchange.sendResponseHeaders(status, body.length);
        if (body.length > 0) {
            try (var output = exchange.getResponseBody()) { output.write(body); }
        }
    }

    private static void cors(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !origin.isBlank() && origin.indexOf('\r') < 0 && origin.indexOf('\n') < 0) {
            headers.set("Access-Control-Allow-Origin", origin);
            headers.set("Vary", "Origin");
        } else {
            headers.set("Access-Control-Allow-Origin", "*");
        }
        headers.set("Access-Control-Allow-Headers", "Content-Type, Idempotency-Key, web-token, game-id");
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
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
        if (name.endsWith(".ico")) return "image/x-icon";
        if (name.endsWith(".bin")) return "application/octet-stream";
        if (name.endsWith(".plist")) return "application/xml";
        return "application/octet-stream";
    }

    private static String hostPort(HttpExchange exchange) {
        String authority = exchange.getRequestHeaders().getFirst("Host");
        if (authority == null || authority.isBlank()) {
            authority = exchange.getLocalAddress().getHostString() + ":" + exchange.getLocalAddress().getPort();
        }
        return authority;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                if (!result.containsKey("config")) result.put("config", args[i]);
                continue;
            }
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
        result.setProperty("redis.host", "18.234.101.161");
        result.setProperty("redis.port", "8021");
        result.setProperty("redis.database", "0");
        result.setProperty("redis.game-id", "8000041");
        result.setProperty("redis.ssl", "false");
        result.setProperty("redis.connect-timeout-ms", "5000");
        result.setProperty("redis.socket-timeout-ms", "30000");
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
        if (config.containsKey("port") || config.containsKey("controller.api-port")) {
            throw new IllegalArgumentException("managed Controller forbids hard-coded port settings");
        }
    }

    private static Path resolvePublish(String value) throws Exception {
        if (value != null && !value.isBlank()) {
            Path direct = Path.of(value).toAbsolutePath().normalize();
            if (Files.isRegularFile(direct.resolve("index.html"))) return direct;
        }
        Path jar = Path.of(LuckyPandaController.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath();
        Path root = Files.isRegularFile(jar) ? jar.getParent().getParent().getParent().getParent()
                : Path.of("D:/work/hd/cpgame");
        Path derived = root.resolve("publish/41-Lucky-Panda").normalize();
        if (!Files.isRegularFile(derived.resolve("index.html"))) {
            throw new IllegalArgumentException("publish/index.html not found: " + derived);
        }
        return derived;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }
}
