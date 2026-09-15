package com.cpgame.sambasensation.server;

import com.cpgame.sambasensation.core.GameRuleCore;
import com.cpgame.sambasensation.generator.RuntimeSpinGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;

/** Controller v3：原静态页面和全部 API 共用一个受管 Java PID、一个平台注入端口。 */
public final class SambaSensationController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path publishRoot;
    private final SambaSensationService service;

    private SambaSensationController(Path publishRoot, SambaSensationService service) {
        this.publishRoot = publishRoot;
        this.service = service;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        int port = requiredPort(options);
        Properties config = loadConfig(options.get("config"));
        rejectUnsafeConfig(config);
        SharedRuleCoreContract.verify();
        Path publish = resolvePublish(options.getOrDefault("publish", config.getProperty("publish.directory", "")));
        RedisRoundStore redis;
        try {
            redis = RedisRoundStore.connect(config);
        } catch (Exception error) {
            throw new IllegalStateException("Redis connection/configuration failure at 18.234.101.161:8021 db=15: " + error.getMessage(), error);
        }
        BigDecimal initial = new BigDecimal(config.getProperty("session.initial-balance", "10000.00"));
        SecureRandom random = new SecureRandom();
        RuntimeRoundComposer composer = new RuntimeRoundComposer(redis,
                new RuntimeSpinGenerator(DemoRuntimePolicy.generatorParameters()),
                DemoRuntimePolicy.PAYOUT_CAP_ODDS, DemoRuntimePolicy.PAYOUT_CAP_WEIGHTS,
                DemoRuntimePolicy.NATURAL_MARY_WEIGHT, DemoRuntimePolicy.ORDINARY_WEIGHT,
                DemoRuntimePolicy.WIN_WEIGHT, DemoRuntimePolicy.LOSS_WEIGHT);
        SambaSensationService service = new SambaSensationService(redis, initial, random, composer);
        SambaSensationController controller = new SambaSensationController(publish, service);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", controller::handle);
        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            try { redis.close(); } catch (IOException ignored) { }
        }, "samba-sensation-controller-stop"));
        server.start();
        System.out.printf("CONTROLLER_READY gameId=2290 port=%d pid=%d rulesVersion=%s rulesHash=%s publish=%s redis=18.234.101.161:8021 db=15%n",
                port, ProcessHandle.current().pid(), GameRuleCore.RULES_VERSION, GameRuleCore.RULES_HASH, publish);
    }

    void handle(HttpExchange exchange) throws IOException {
        try {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) { sendEmpty(exchange, 204); return; }
            String path = exchange.getRequestURI().getPath();
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod()) || "HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                Map<String, String> values = query(exchange.getRequestURI().getRawQuery());
                if ("/__controller/status".equals(path) || "/health".equals(path) || "/api/session".equals(path)) {
                    sendJson(exchange, 200, service.status(values)); return;
                }
                if ("/api/balance".equals(path)) { sendJson(exchange, 200, service.balance(values)); return; }
                if ("/api/report/timing".equals(path)) { sendEmpty(exchange, 204); return; }
                serveStatic(exchange); return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { sendJson(exchange, 405, SambaSensationService.error(405, "POST required")); return; }
            Map<String, String> form = form(exchange);
            switch (path) {
                case "/cp/config/initialData", "/config/initialData" -> {
                    HostPort hp = hostPort(exchange); sendJson(exchange, 200, service.config(form, hp.host(), hp.port()));
                }
                case "/cp/account/getUserInfo", "/account/getUserInfo" -> sendJson(exchange, 200, service.user(form));
                case "/cp/activity/getActivity", "/activity/getActivity" -> sendJson(exchange, 200, service.activity());
                case "/cp/single_game.Game/initRoom", "/single_game.Game/initRoom" -> sendJson(exchange, 200, service.init(form));
                case "/cp/single_game.Game/gameResult", "/single_game.Game/gameResult" ->
                        sendJson(exchange, 200, service.spin(form, exchange.getRequestHeaders().getFirst("Idempotency-Key")));
                case "/cp/goldgame/single_game_user_gold_history", "/goldgame/single_game_user_gold_history" ->
                        sendJson(exchange, 200, service.historySummary(form));
                case "/cp/goldgame/single_game_user_history", "/goldgame/single_game_user_history" ->
                        sendJson(exchange, 200, service.historyDetail(form));
                case "/api/balance" -> sendJson(exchange, 200, service.balance(form));
                case "/api/session" -> sendJson(exchange, 200, service.status(form));
                case "/api/report/timing" -> sendEmpty(exchange, 204);
                default -> sendJson(exchange, 404, SambaSensationService.error(404, "unknown endpoint: " + path));
            }
        } catch (IOException error) {
            sendJson(exchange, 200, SambaSensationService.error(503,
                    "Redis connection/configuration failure at 18.234.101.161:8021 db=15: " + error.getMessage()));
        } catch (IllegalStateException error) {
            sendJson(exchange, 200, SambaSensationService.error(503, error.getMessage()));
        } catch (IllegalArgumentException error) {
            sendJson(exchange, 200, SambaSensationService.error(400, error.getMessage()));
        } catch (Exception error) {
            error.printStackTrace(System.err);
            sendJson(exchange, 200, SambaSensationService.error(500, "controller failure"));
        } finally {
            exchange.close();
        }
    }

    private void serveStatic(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        if ("/".equals(uri.getPath()) && needsRewrite(uri.getRawQuery(), exchange)) {
            exchange.getResponseHeaders().set("Location", "/?" + rewrittenQuery(uri.getRawQuery(), exchange));
            sendEmpty(exchange, 302); return;
        }
        String rawPath = uri.getPath(); if (rawPath.isEmpty() || "/".equals(rawPath)) rawPath = "/index.html";
        Path file = publishRoot.resolve(rawPath.substring(1)).normalize();
        if (!file.startsWith(publishRoot) || !Files.isRegularFile(file)) {
            sendJson(exchange, 404, SambaSensationService.error(404, "static file not found")); return;
        }
        byte[] body = Files.readAllBytes(file);
        cors(exchange);
        exchange.getResponseHeaders().set("Content-Type", mime(file));
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) { exchange.sendResponseHeaders(200, -1); return; }
        send(exchange, 200, body);
    }

    private static boolean needsRewrite(String rawQuery, HttpExchange exchange) {
        Map<String, String> q = query(rawQuery);
        String authority = hostPort(exchange).authority();
        return !"2290".equals(q.get("gid")) || !authority.equalsIgnoreCase(q.getOrDefault("sip", ""))
                || !q.containsKey("t") || !q.containsKey("token") || !q.containsKey("language");
    }

    private static String rewrittenQuery(String rawQuery, HttpExchange exchange) {
        Map<String, String> q = new LinkedHashMap<>(query(rawQuery));
        q.putIfAbsent("ai", "luck_single_2290"); q.putIfAbsent("btt", "1"); q.put("gid", "2290");
        q.putIfAbsent("l", "en"); q.putIfAbsent("language", "en-us"); q.put("sip", hostPort(exchange).authority());
        q.putIfAbsent("t", "local-replay"); q.putIfAbsent("token", q.get("t"));
        return q.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .reduce((a, b) -> a + "&" + b).orElse("gid=2290");
    }

    private static Map<String, String> form(HttpExchange exchange) throws IOException {
        Map<String, String> result = new LinkedHashMap<>(query(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        result.putAll(query(exchange.getRequestURI().getRawQuery()));
        return result;
    }
    private static Map<String, String> query(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return result;
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2); result.put(decode(parts[0]), parts.length > 1 ? decode(parts[1]) : "");
        }
        return result;
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String decode(String value) { return URLDecoder.decode(value, StandardCharsets.UTF_8); }

    private static void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        cors(exchange); exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store"); send(exchange, status, JSON.writeValueAsBytes(value));
    }
    private static void sendEmpty(HttpExchange exchange, int status) throws IOException { cors(exchange); send(exchange, status, new byte[0]); }
    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        if (body.length > 0) exchange.getResponseBody().write(body);
    }
    private static void cors(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders(); String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !origin.isBlank() && origin.indexOf('\r') < 0 && origin.indexOf('\n') < 0) {
            headers.set("Access-Control-Allow-Origin", origin); headers.set("Vary", "Origin");
        } else headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Headers", "Content-Type, Idempotency-Key");
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS, HEAD");
    }
    private static String mime(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".ttf")) return "font/ttf";
        if (name.endsWith(".plist")) return "application/xml";
        return "application/octet-stream";
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) { if (!result.containsKey("config")) result.put("config", args[i]); continue; }
            String key = args[i].substring(2);
            String value = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true";
            result.put(key, value);
        }
        return result;
    }
    private static int requiredPort(Map<String, String> options) {
        String raw = firstNonBlank(options.get("port"), System.getProperty("cpgame.demo.port"), System.getenv("CPGAME_DEMO_PORT"));
        if (raw == null) throw new IllegalArgumentException("platform must inject --port in range 50000-59999");
        int port = Integer.parseInt(raw); if (port < 50000 || port > 59999) throw new IllegalArgumentException("port must be 50000-59999"); return port;
    }
    private static Properties loadConfig(String value) throws IOException {
        Properties result = new Properties();
        result.setProperty("redis.host", "18.234.101.161"); result.setProperty("redis.port", "8021");
        result.setProperty("redis.database", "0"); result.setProperty("redis.game-id", "8002290");
        result.setProperty("redis.ssl", "false"); result.setProperty("redis.connect-timeout-ms", "5000");
        result.setProperty("redis.socket-timeout-ms", "30000");
        if (value == null || value.isBlank()) return result;
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("config not found: " + path);
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { result.load(reader); }
        return result;
    }
    private static void rejectUnsafeConfig(Properties config) {
        for (String key : config.stringPropertyNames()) if (key.toLowerCase(Locale.ROOT).contains("seed"))
            throw new IllegalArgumentException("formal Controller config must not contain " + key);
        if (config.containsKey("port") || config.containsKey("controller.api-port"))
            throw new IllegalArgumentException("managed Controller forbids hard-coded port settings");
        if (!"18.234.101.161".equals(config.getProperty("redis.host"))
                || !"8021".equals(config.getProperty("redis.port")) || Integer.parseInt(config.getProperty("redis.database")) < 0)
            throw new IllegalArgumentException("Demo Redis 固定为 18.234.101.161:8021 db=15");
    }
    private static Path resolvePublish(String value) throws Exception {
        if (value != null && !value.isBlank()) {
            Path direct = Path.of(value).toAbsolutePath().normalize(); if (Files.isRegularFile(direct.resolve("index.html"))) return direct;
        }
        Path jar = Path.of(SambaSensationController.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
        Path root = Files.isRegularFile(jar) ? jar.getParent().getParent().getParent().getParent() : Path.of("D:/work/hd/cpgame");
        Path derived = root.resolve("publish/2290-Samba-Sensation").normalize();
        if (!Files.isRegularFile(derived.resolve("index.html"))) throw new IllegalArgumentException("publish/index.html not found: " + derived);
        return derived;
    }
    private static HostPort hostPort(HttpExchange exchange) {
        String authority = exchange.getRequestHeaders().getFirst("Host");
        if (authority == null || authority.isBlank()) authority = exchange.getLocalAddress().getHostString() + ":" + exchange.getLocalAddress().getPort();
        String host = authority; int port = exchange.getLocalAddress().getPort(); int colon = authority.lastIndexOf(':');
        if (colon > 0 && authority.indexOf(':') == colon) { host = authority.substring(0, colon); try { port = Integer.parseInt(authority.substring(colon + 1)); } catch (NumberFormatException ignored) { } }
        return new HostPort(host, port);
    }
    private static String firstNonBlank(String... values) { for (String value : values) if (value != null && !value.isBlank()) return value; return null; }
    private record HostPort(String host, int port) { String authority() { return host + ":" + port; } }
}
