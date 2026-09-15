package com.cpgame.crazy777.server;

import com.cpgame.crazy777.generator.GameRules;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public final class Crazy777Server implements AutoCloseable {
    private final AppConfig config;
    private final SessionService sessions;
    private HttpServer server;

    public Crazy777Server(AppConfig config) {
        this.config = config;
        this.sessions = new SessionService(config.initialBalance(), new RedisRoundStore(config));
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.address(), config.port()), 128);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            addCommonHeaders(exchange);
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) { sendEmpty(exchange, 204); return; }
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/cp/api/")) handleApi(exchange, path);
            else serveStatic(exchange);
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 200, ProtocolCodec.envelope(400, null, e.getMessage()));
        } catch (RedisRoundStore.PoolUnavailableException e) {
            sendJson(exchange, 503, localError(e.code(), e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace(System.err);
            sendJson(exchange, 500, localError("LOCAL_SERVER_ERROR", e.getMessage()));
        } finally {
            exchange.close();
        }
    }

    private void handleApi(HttpExchange exchange, String path) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendJson(exchange, 405, ProtocolCodec.envelope(405, null, "仅支持 POST"));
            return;
        }
        Map<String, String> form = ProtocolCodec.readForm(exchange);
        if (!"57".equals(form.get("gid"))) {
            sendJson(exchange, 200, ProtocolCodec.authFailure());
            return;
        }
        if (path.equals("/cp/api/v1/auth/verify")) { verify(exchange, form); return; }
        PlayerSession session = sessions.authenticate(form.get("t"));
        if (session == null) { sendJson(exchange, 200, ProtocolCodec.authFailure()); return; }
        switch (path) {
            case "/cp/api/v1/crazy-seven/config", "/cp/api/v1/crazy-seven/init" -> config(exchange, session);
            case "/cp/api/v1/crazy-seven/spin" -> spin(exchange, session, form);
            case "/cp/api/v1/crazy-seven/log-list" -> historyList(exchange, session, form);
            case "/cp/api/v1/crazy-seven/log-view" -> historyDetail(exchange, session, form);
            case "/cp/api/v1/balance", "/cp/api/v1/crazy-seven/balance" -> balance(exchange, session);
            case "/cp/api/v1/session", "/cp/api/v1/crazy-seven/session" -> session(exchange, session);
            case "/cp/api/v1/ping" -> sendJson(exchange, 200, ProtocolCodec.success(Map.of("ts", System.currentTimeMillis() / 1000)));
            default -> sendJson(exchange, 404, ProtocolCodec.envelope(404, null, path));
        }
    }

    private void verify(HttpExchange exchange, Map<String, String> form) throws IOException {
        PlayerSession session = sessions.verify(form.get("t"));
        if (session == null) { sendJson(exchange, 200, ProtocolCodec.authFailure()); return; }
        Map<String, Object> player = new LinkedHashMap<>();
        player.put("balance", session.balance().stripTrailingZeros().toPlainString());
        player.put("id", session.playerId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("player", player);
        data.put("token", session.runtimeToken());
        data.put("ping", Map.of("enable", 0, "seconds", 0));
        data.put("rc", Map.of("on", 0, "v", 2));
        data.put("gc", Map.of("os", 1, "om", 1));
        sendJson(exchange, 200, ProtocolCodec.success(data));
    }

    private void config(HttpExchange exchange, PlayerSession session) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("auto", List.of(10, 30, 50, 100, 500));
        data.put("bll", GameRules.BET_LEVELS);
        data.put("bsl", GameRules.BET_SIZES);
        data.put("cc", "BRL");
        data.put("cs", "R$");
        data.put("dbl", 50);
        data.put("dbs", new BigDecimal("0.5"));
        data.put("last", session.last());
        data.put("spl", GameRules.PAYTABLE);
        data.put("ts", System.currentTimeMillis() / 1000);
        sendJson(exchange, 200, ProtocolCodec.success(data));
    }

    private void spin(HttpExchange exchange, PlayerSession session, Map<String, String> form) throws IOException {
        try {
            BigDecimal bs = new BigDecimal(required(form, "bs"));
            int bl = Integer.parseInt(required(form, "bl"));
            String key = firstNonBlank(form.get("request_id"), form.get("idempotency_key"),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            sendJson(exchange, 200, ProtocolCodec.success(session.spin(bs, bl, key)));
        } catch (IllegalStateException e) {
            if ("INSUFFICIENT_BALANCE".equals(e.getMessage())) {
                sendJson(exchange, 200, ProtocolCodec.envelope(400, null, "余额不足"));
            } else throw e;
        }
    }

    private void historyList(HttpExchange exchange, PlayerSession session, Map<String, String> form) throws IOException {
        int page = Integer.parseInt(form.getOrDefault("page_index", "1"));
        sendJson(exchange, 200, ProtocolCodec.success(session.historyList(page)));
    }

    private void historyDetail(HttpExchange exchange, PlayerSession session, Map<String, String> form) throws IOException {
        Map<String, Object> detail = session.historyDetail(required(form, "transfer_id"));
        if (detail == null) {
            sendJson(exchange, 200, ProtocolCodec.envelope(404, null, "transfer_id 不存在"));
            return;
        }
        sendJson(exchange, 200, ProtocolCodec.success(detail));
    }

    private void balance(HttpExchange exchange, PlayerSession session) throws IOException {
        sendJson(exchange, 200, ProtocolCodec.success(Map.of("player",
                Map.of("id", session.playerId(), "balance", session.balance().stripTrailingZeros().toPlainString()))));
    }

    private void session(HttpExchange exchange, PlayerSession session) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("player", Map.of("id", session.playerId(),
                "balance", session.balance().stripTrailingZeros().toPlainString()));
        data.put("roundKey", session.lastRoundKey());
        data.put("deliveryIndex", session.deliveryIndex());
        data.put("historyCount", session.historyCount());
        data.put("state", "SESSION_READY");
        sendJson(exchange, 200, ProtocolCodec.success(data));
    }

    private void serveStatic(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET") && !exchange.getRequestMethod().equalsIgnoreCase("HEAD")) {
            sendEmpty(exchange, 405);
            return;
        }
        URI uri = exchange.getRequestURI();
        String requestPath = uri.getPath().equals("/") ? "/index.html" : uri.getPath();
        if ((requestPath.equals("/index.html") || requestPath.equals("/")) && queryValue(uri.getRawQuery(), "sip") == null) {
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (host == null || host.isBlank()) host = "localhost:" + config.port();
            String query = uri.getRawQuery();
            String target = "/?" + (query == null || query.isBlank() ? "" : query + "&")
                    + "sip=" + URLEncoder.encode(host, StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Location", target);
            sendEmpty(exchange, 302);
            return;
        }
        if (requestPath.equals("/")) requestPath = "/index.html";
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
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers",
                "Content-Type, web-token, game-id, Idempotency-Key");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,HEAD,POST,OPTIONS");
    }

    private static Map<String, Object> localError(String code, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("cd", code);
        err.put("msg", message == null ? "" : message);
        err.put("tid", Long.toUnsignedString(System.nanoTime(), 36).toUpperCase());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dt", null);
        result.put("err", err);
        return result;
    }

    private static String required(Map<String, String> form, String key) {
        String value = form.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少字段 " + key);
        return value;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
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
        return "application/octet-stream";
    }

    @Override public void close() {
        if (server != null) server.stop(0);
    }
}
