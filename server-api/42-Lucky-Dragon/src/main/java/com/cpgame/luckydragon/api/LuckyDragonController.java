package com.cpgame.luckydragon.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class LuckyDragonController {
    private final LuckyDragonService service;

    LuckyDragonController(LuckyDragonService service) { this.service = service; }

    void register(HttpServer server) {
        route(server, "/cp/api/v1/auth/verify", (form, exchange) -> service.auth(form, launchAlias(exchange)));
        route(server, "/cp/api/v1/lucky-dragon/config", (form, exchange) -> service.config(form));
        route(server, "/cp/api/v1/lucky-dragon/spin", (form, exchange) ->
            service.spin(form, exchange.getRequestHeaders().getFirst("Idempotency-Key")));
        route(server, "/cp/api/v1/lucky-dragon/log-list", (form, exchange) -> service.historyList(form));
        route(server, "/cp/api/v1/lucky-dragon/log-view", (form, exchange) -> service.historyDetail(form));
        route(server, "/cp/api/v1/lucky-dragon/balance", (form, exchange) -> service.balance(form));
        route(server, "/cp/api/v1/ping", (form, exchange) -> service.ping(form));
        server.createContext("/__controller/status", exchange -> send(exchange, 200, service.status()));
    }

    private void route(HttpServer server, String path, Action action) {
        server.createContext(path, exchange -> {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) { preflight(exchange); return; }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, error(405, "POST required")); return;
            }
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                send(exchange, 200, action.apply(FormCodec.parse(body), exchange));
            } catch (ApiException error) {
                send(exchange, error.status, error(error.status, error.getMessage()));
            } catch (IllegalArgumentException error) {
                send(exchange, 400, error(400, error.getMessage()));
            } catch (Exception error) {
                send(exchange, 500, error(500, "controller failure"));
            }
        });
    }

    private static Map<String,Object> error(int status, String message) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("code", status); result.put("data", Map.of()); result.put("info", message);
        return result;
    }

    private static void preflight(HttpExchange exchange) throws IOException {
        cors(exchange);
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    static void send(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = JsonCodec.write(value).getBytes(StandardCharsets.UTF_8);
        cors(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }

    private static void cors(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (isHttpOrigin(origin)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            exchange.getResponseHeaders().set("Vary", "Origin");
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Idempotency-Key");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
    }

    private static boolean isHttpOrigin(String origin) {
        if (origin == null || origin.length() > 2048 || origin.indexOf('\r') >= 0 || origin.indexOf('\n') >= 0) return false;
        try {
            URI value = URI.create(origin);
            return ("http".equalsIgnoreCase(value.getScheme()) || "https".equalsIgnoreCase(value.getScheme()))
                && value.getHost() != null && value.getRawPath().isEmpty()
                && value.getRawQuery() == null && value.getRawFragment() == null;
        } catch (IllegalArgumentException ignored) { return false; }
    }

    private static String launchAlias(HttpExchange exchange) {
        String referer = exchange.getRequestHeaders().getFirst("Referer");
        if (referer == null) return null;
        try {
            URI uri = URI.create(referer);
            Map<String,String> query = FormCodec.parse(uri.getRawQuery() == null ? "" : uri.getRawQuery());
            String value = query.get("t");
            return value == null || value.isBlank() ? null : value;
        } catch (IllegalArgumentException ignored) { return null; }
    }

    @FunctionalInterface private interface Action {
        Object apply(Map<String,String> form, HttpExchange exchange) throws Exception;
    }
}
