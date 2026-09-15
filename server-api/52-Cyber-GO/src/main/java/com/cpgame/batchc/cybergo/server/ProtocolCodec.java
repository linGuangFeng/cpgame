package com.cpgame.batchc.cybergo.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** B10：application/x-www-form-urlencoded 与 code/data/info JSON 信封。 */
final class ProtocolCodec {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ProtocolCodec() { }

    static Map<String, String> form(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> result = new LinkedHashMap<>();
        if (body.isBlank()) return result;
        for (String pair : body.split("&")) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            result.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return result;
    }

    static void success(HttpExchange exchange, Object data) throws IOException { envelope(exchange, 200, 200, "ok", data); }

    static void failure(HttpExchange exchange, int httpStatus, int code, String info) throws IOException {
        envelope(exchange, httpStatus, code, info, Map.of());
    }

    private static void envelope(HttpExchange exchange, int httpStatus, int code, String info, Object data) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(Map.of("code", code, "data", data, "info", info));
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Idempotency-Key");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.sendResponseHeaders(httpStatus, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    static void options(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Idempotency-Key");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }
}
