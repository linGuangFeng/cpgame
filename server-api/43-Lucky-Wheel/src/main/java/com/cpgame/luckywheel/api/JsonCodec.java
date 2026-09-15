package com.cpgame.luckywheel.api;

import java.util.Iterator;
import java.util.Map;

public final class JsonCodec {
    private JsonCodec() { }

    public static String write(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return '"' + escape(s) + '"';
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            Iterator<? extends Map.Entry<?, ?>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> entry = it.next();
                out.append(write(String.valueOf(entry.getKey()))).append(':').append(write(entry.getValue()));
                if (it.hasNext()) out.append(',');
            }
            return out.append('}').toString();
        }
        if (value instanceof Iterable<?> values) {
            StringBuilder out = new StringBuilder("[");
            Iterator<?> it = values.iterator();
            while (it.hasNext()) { out.append(write(it.next())); if (it.hasNext()) out.append(','); }
            return out.append(']').toString();
        }
        throw new IllegalArgumentException("不支持的JSON类型: " + value.getClass());
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
            }
        }
        return out.toString();
    }
}
