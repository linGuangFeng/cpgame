package com.cpgame.luckycatii.server;

import com.cpgame.luckycatii.GameRules;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public final class LuckyCatIIServer implements AutoCloseable {
    private static final String PROVIDER_PROPERTY = "com.sun.net.httpserver.HttpServerProvider";
    private static final String REQUIRED_PROVIDER = "com.lgf.agentai.service.CpgameSharedHttpServerProvider";

    private final AppConfig config;
    private final SessionService sessions;
    private HttpServer server;
    private final boolean shared;

    public LuckyCatIIServer(AppConfig config) {
        this.config = config;
        this.sessions = new SessionService(config.initialBalance(), new RedisRoundStore(config));
        this.shared = REQUIRED_PROVIDER.equals(System.getProperty(PROVIDER_PROPERTY, ""));
    }

    public void start() throws IOException {
        if (shared) {
            server = HttpServer.create(InetSocketAddress.createUnresolved("shared-http", 1), 0);
        } else {
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", config.port()), 128);
        }
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            addCommonHeaders(exchange);
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                sendEmpty(exchange, 204);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if ("/health".equals(path) || "/__controller/status".equals(path)) {
                sendJson(exchange, 200, ProtocolCodec.success(Map.of(
                        "gameId", GameRules.GAME_ID, "rulesHash", GameRules.RULES_HASH, "port", config.port())));
                return;
            }
            if (path.startsWith("/cp/api/") || path.startsWith("/api/")) handleApi(exchange, path);
            else serveStatic(exchange);
        } catch (UnauthorizedException e) {
            sendJson(exchange, 200, ProtocolCodec.business(401, ""));
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 200, ProtocolCodec.business(400, ""));
        } catch (RedisRoundStore.PoolUnavailableException e) {
            sendJson(exchange, 200, ProtocolCodec.business(503, ""));
        } catch (Exception e) {
            e.printStackTrace(System.err);
            sendJson(exchange, 200, ProtocolCodec.business(500, ""));
        } finally {
            exchange.close();
        }
    }

    private void handleApi(HttpExchange exchange, String path) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendJson(exchange, 405, ProtocolCodec.business(405, ""));
            return;
        }
        Map<String, String> form = ProtocolCodec.readForm(exchange);
        if (!"50".equals(form.get("gid"))) {
            sendJson(exchange, 200, ProtocolCodec.business(400, ""));
            return;
        }
        String normalized = path.startsWith("/cp") ? path.substring(3) : path;
        switch (normalized) {
            case "/api/v1/auth/verify" -> verify(exchange, form);
            case "/api/v1/auth/session" -> sendJson(exchange, 200, ProtocolCodec.business(501, ""));
            case "/api/v1/golden-cat/config" -> config(exchange, form);
            case "/api/v1/golden-cat/spin" -> spin(exchange, form);
            case "/api/v1/golden-cat/log-list" -> historyList(exchange, form);
            case "/api/v1/golden-cat/log-view" -> historyDetail(exchange, form);
            case "/api/v1/ping" -> sendJson(exchange, 200, ProtocolCodec.success(Map.of("ts", System.currentTimeMillis() / 1000)));
            case "/api/v1/golden-cat/balance", "/api/balance" -> balance(exchange, form);
            case "/api/session" -> session(exchange, form);
            default -> sendJson(exchange, 200, ProtocolCodec.business(404, ""));
        }
    }

    private void verify(HttpExchange exchange, Map<String, String> form) throws IOException {
        PlayerSession session = sessions.verify(form.get("t"));
        if (session == null) {
            sendJson(exchange, 200, ProtocolCodec.business(401, ""));
            return;
        }
        Map<String, Object> player = new LinkedHashMap<>();
        player.put("id", session.playerId());
        player.put("balance", SpinWireMapper.number(session.balance()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("player", player);
        data.put("token", session.token());
        data.put("ping", Map.of("enable", 0, "seconds", 0));
        data.put("rc", Map.of("on", 0, "v", 2));
        data.put("gc", Map.of("os", 1, "om", 1));
        sendJson(exchange, 200, ProtocolCodec.success(data));
    }

    private void config(HttpExchange exchange, Map<String, String> form) throws IOException {
        PlayerSession session = requireSession(form);
        sendJson(exchange, 200, ProtocolCodec.success(SpinWireMapper.configBody(session.last())));
    }

    private void spin(HttpExchange exchange, Map<String, String> form) throws IOException {
        PlayerSession session = requireSession(form);
        try {
            BigDecimal bs = new BigDecimal(required(form, "bs"));
            int bl = Integer.parseInt(required(form, "bl"));
            String key = firstNonBlank(form.get("request_id"), form.get("idempotency_key"),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            sendJson(exchange, 200, ProtocolCodec.success(session.spin(bs, bl, key)));
        } catch (IllegalStateException e) {
            if ("INSUFFICIENT_BALANCE".equals(e.getMessage())) sendJson(exchange, 200, ProtocolCodec.business(400, ""));
            else throw e;
        }
    }

    private void historyList(HttpExchange exchange, Map<String, String> form) throws IOException {
        PlayerSession session = requireSession(form);
        int page = (int) parseEpoch(form.getOrDefault("page_index", "1"));
        if (page < 1) page = 1;
        long begin = parseEpoch(form.getOrDefault("begin_at", "0"));
        long end = parseEpoch(form.getOrDefault("end_at", "0"));
        sendJson(exchange, 200, ProtocolCodec.success(session.historyList(page, begin, end)));
    }

    private void historyDetail(HttpExchange exchange, Map<String, String> form) throws IOException {
        PlayerSession session = requireSession(form);
        Map<String, Object> detail = session.historyDetail(required(form, "transfer_id"));
        if (detail == null) sendJson(exchange, 200, ProtocolCodec.business(404, ""));
        else sendJson(exchange, 200, ProtocolCodec.success(detail));
    }

    private void balance(HttpExchange exchange, Map<String, String> form) throws IOException {
        PlayerSession session = requireSession(form);
        sendJson(exchange, 200, ProtocolCodec.success(Map.of("player",
                Map.of("id", session.playerId(), "balance", SpinWireMapper.number(session.balance())))));
    }

    private void session(HttpExchange exchange, Map<String, String> form) throws IOException {
        PlayerSession session = requireSession(form);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("player", Map.of("id", session.playerId(), "balance", SpinWireMapper.number(session.balance())));
        data.put("historyCount", session.historyCount());
        data.put("state", "SESSION_READY");
        sendJson(exchange, 200, ProtocolCodec.success(data));
    }

    private PlayerSession requireSession(Map<String, String> form) {
        PlayerSession session = sessions.authenticate(form.get("t"));
        if (session == null) throw new UnauthorizedException();
        return session;
    }

    static final class UnauthorizedException extends RuntimeException {}

    private void serveStatic(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET") && !exchange.getRequestMethod().equalsIgnoreCase("HEAD")) {
            sendEmpty(exchange, 405);
            return;
        }
        URI uri = exchange.getRequestURI();
        String requestPath = uri.getPath().equals("/") ? "/index.html" : uri.getPath();
        if ((uri.getPath().equals("/") || uri.getPath().equals("/index.html")) && queryValue(uri.getRawQuery(), "sip") == null) {
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (host == null || host.isBlank()) host = "localhost:" + config.port();
            String query = uri.getRawQuery();
            String target = "/?" + (query == null || query.isBlank() ? "" : query + "&")
                    + "sip=" + URLEncoder.encode(host, StandardCharsets.UTF_8);
            if (queryValue(query, "gid") == null) target += "&gid=50";
            if (queryValue(query, "t") == null) target += "&t=local-demo-token";
            exchange.getResponseHeaders().set("Location", target);
            sendEmpty(exchange, 302);
            return;
        }
        Path target = config.publishDirectory().resolve(requestPath.substring(1)).normalize();
        if (!target.startsWith(config.publishDirectory()) || !Files.isRegularFile(target)) {
            sendEmpty(exchange, 404);
            return;
        }
        byte[] bytes = Files.readAllBytes(target);
        exchange.getResponseHeaders().set("Content-Type", mime(target));
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (exchange.getRequestMethod().equalsIgnoreCase("HEAD")) sendEmpty(exchange, 200);
        else {
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = ProtocolCodec.jsonBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private void sendEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    private void addCommonHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, web-token, game-id, Idempotency-Key");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,HEAD,POST,OPTIONS");
    }

    private static String required(Map<String, String> form, String key) {
        String value = form.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少字段 " + key);
        return value;
    }

    private static long parseEpoch(String raw) {
        if (raw == null || raw.isBlank()) return 0L;
        try {
            return new BigDecimal(raw.trim()).longValue();
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private static String queryValue(String rawQuery, String key) {
        if (rawQuery == null) return null;
        for (String pair : rawQuery.split("&")) {
            String[] p = pair.split("=", 2);
            if (p[0].equals(key)) return p.length == 2 ? p[1] : "";
        }
        return null;
    }

    private static String mime(Path path) {
        String n = path.getFileName().toString().toLowerCase();
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (n.endsWith(".json")) return "application/json; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".bin")) return "application/octet-stream";
        if (n.endsWith(".plist")) return "application/xml";
        if (n.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    @Override public void close() {
        if (server != null) server.stop(0);
    }
}
