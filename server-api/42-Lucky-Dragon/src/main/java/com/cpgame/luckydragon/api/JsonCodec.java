package com.cpgame.luckydragon.api;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.Map;

final class JsonCodec {
    private JsonCodec() { }

    static String write(Object value) {
        StringBuilder output = new StringBuilder();
        append(output, value);
        return output.toString();
    }

    private static void append(StringBuilder output, Object value) {
        if (value == null) { output.append("null"); return; }
        if (value instanceof String text) { string(output, text); return; }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long
            || value instanceof Short || value instanceof Byte || value instanceof BigDecimal) {
            output.append(value); return;
        }
        if (value instanceof Number number) {
            double decimal = number.doubleValue();
            output.append(Double.isFinite(decimal) ? number : "null");
            return;
        }
        if (value instanceof Map<?,?> map) {
            output.append('{');
            boolean comma = false;
            for (Map.Entry<?,?> entry : map.entrySet()) {
                if (comma) output.append(',');
                string(output, String.valueOf(entry.getKey()));
                output.append(':');
                append(output, entry.getValue());
                comma = true;
            }
            output.append('}');
            return;
        }
        if (value instanceof Iterable<?> items) {
            output.append('[');
            boolean comma = false;
            for (Object item : items) {
                if (comma) output.append(',');
                append(output, item);
                comma = true;
            }
            output.append(']');
            return;
        }
        if (value.getClass().isArray()) {
            output.append('[');
            for (int index = 0; index < Array.getLength(value); index++) {
                if (index > 0) output.append(',');
                append(output, Array.get(value, index));
            }
            output.append(']');
            return;
        }
        string(output, value.toString());
    }

    private static void string(StringBuilder output, String value) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) output.append(String.format("\\u%04x", (int) character));
                    else output.append(character);
                }
            }
        }
        output.append('"');
    }
}
