package com.hd.cpgame.magicscroll2.api.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class HttpResponses {
    private HttpResponses() {}

    public static void envelope(HttpExchange exchange, Object data) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<String, Object>();
        envelope.put("dt", data);
        envelope.put("err", null);
        json(exchange, 200, envelope);
    }

    public static void error(HttpExchange exchange, int status, String code, String message) throws Exception {
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> envelope = new LinkedHashMap<String, Object>();
        envelope.put("dt", null);
        envelope.put("err", error);
        json(exchange, status, envelope);
    }

    public static void json(HttpExchange exchange, int status, Object value) throws Exception {
        byte[] body = Json.encode(value).getBytes(StandardCharsets.UTF_8);
        cors(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(body); }
    }

    public static void options(HttpExchange exchange) throws Exception {
        cors(exchange);
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    public static void cors(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin == null ? "*" : origin);
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers",
                "Content-Type, Idempotency-Key, X-Request-Id");
        exchange.getResponseHeaders().set("Vary", "Origin, Host");
    }
}
