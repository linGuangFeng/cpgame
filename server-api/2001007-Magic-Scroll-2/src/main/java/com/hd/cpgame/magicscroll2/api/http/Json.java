package com.hd.cpgame.magicscroll2.api.http;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map;

public final class Json {
    private Json() {}
    public static String encode(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }
    private static void append(StringBuilder out, Object value) {
        if (value == null) out.append("null");
        else if (value instanceof String || value instanceof Character) quote(out, value.toString());
        else if (value instanceof Boolean || value instanceof Number || value instanceof BigDecimal) out.append(value);
        else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Object entryObject : ((Map<?, ?>) value).entrySet()) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) entryObject;
                if (!first) out.append(',');
                first = false;
                quote(out, String.valueOf(entry.getKey()));
                out.append(':');
                append(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable) {
            out.append('[');
            Iterator<?> iterator = ((Iterable<?>) value).iterator();
            boolean first = true;
            while (iterator.hasNext()) {
                if (!first) out.append(',');
                first = false;
                append(out, iterator.next());
            }
            out.append(']');
        } else if (value.getClass().isArray()) {
            out.append('[');
            for (int i = 0; i < Array.getLength(value); i++) {
                if (i > 0) out.append(',');
                append(out, Array.get(value, i));
            }
            out.append(']');
        } else quote(out, value.toString());
    }
    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c);
            }
        }
        out.append('"');
    }
}
