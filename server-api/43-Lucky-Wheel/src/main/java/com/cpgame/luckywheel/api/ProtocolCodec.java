package com.cpgame.luckywheel.api;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ProtocolCodec {
    private ProtocolCodec() { }
    public static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> values = new LinkedHashMap<>();
        if (body.isEmpty()) return values;
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            values.put(key, value);
        }
        return values;
    }
    public static String success(Object data) {
        Map<String,Object> envelope = new LinkedHashMap<>();
        envelope.put("code", 200); envelope.put("data", data); envelope.put("info", "ok");
        return JsonCodec.write(envelope);
    }
    public static String error(int code, String info) {
        Map<String,Object> envelope = new LinkedHashMap<>();
        envelope.put("code", code); envelope.put("data", Map.of()); envelope.put("info", info);
        return JsonCodec.write(envelope);
    }
}
