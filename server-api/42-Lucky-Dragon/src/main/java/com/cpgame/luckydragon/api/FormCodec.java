package com.cpgame.luckydragon.api;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class FormCodec {
    private FormCodec() { }

    static Map<String,String> parse(String body) {
        Map<String,String> values = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return values;
        for (String field : body.split("&")) {
            int equals = field.indexOf('=');
            String key = decode(equals < 0 ? field : field.substring(0, equals));
            String value = decode(equals < 0 ? "" : field.substring(equals + 1));
            values.put(key, value);
        }
        return values;
    }

    private static String decode(String value) { return URLDecoder.decode(value, StandardCharsets.UTF_8); }
}
