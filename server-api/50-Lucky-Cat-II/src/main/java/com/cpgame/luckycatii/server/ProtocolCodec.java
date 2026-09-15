package com.cpgame.luckycatii.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ProtocolCodec {
    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private ProtocolCodec() {}

    public static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> form = new LinkedHashMap<>();
        if (!body.isBlank()) {
            for (String pair : body.split("&")) {
                String[] parts = pair.split("=", 2);
                String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                String value = URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8);
                form.put(key, value);
            }
        }
        if (exchange.getRequestURI().getRawQuery() != null) {
            for (String pair : exchange.getRequestURI().getRawQuery().split("&")) {
                String[] parts = pair.split("=", 2);
                form.putIfAbsent(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8));
            }
        }
        form.putIfAbsent("t", exchange.getRequestHeaders().getFirst("web-token"));
        form.putIfAbsent("gid", exchange.getRequestHeaders().getFirst("game-id"));
        form.values().removeIf(v -> v == null);
        return form;
    }

    public static Map<String, Object> success(Object data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 200);
        result.put("info", "ok");
        result.put("data", data);
        return result;
    }

    public static Map<String, Object> business(int code, String info) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("info", info == null ? "" : info);
        return result;
    }

    public static byte[] jsonBytes(Object value) throws IOException {
        return JSON.writeValueAsBytes(value);
    }
}
