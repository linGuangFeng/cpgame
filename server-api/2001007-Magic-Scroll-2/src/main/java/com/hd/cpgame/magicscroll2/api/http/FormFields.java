package com.hd.cpgame.magicscroll2.api.http;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FormFields {
    private static final Pattern NAME = Pattern.compile("name=\"([^\"]+)\"");
    private FormFields() {}

    public static Map<String, String> parse(HttpExchange exchange) throws Exception {
        byte[] bytes = readLimited(exchange.getRequestBody(), 1024 * 1024);
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null) contentType = "";
        if (contentType.toLowerCase().startsWith("multipart/form-data")) {
            String boundary = boundary(contentType);
            return multipart(bytes, boundary);
        }
        return urlEncoded(new String(bytes, StandardCharsets.UTF_8));
    }

    private static Map<String, String> multipart(byte[] bytes, String boundary) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        String body = new String(bytes, StandardCharsets.ISO_8859_1);
        String[] parts = body.split(Pattern.quote("--" + boundary));
        for (String part : parts) {
            int headerEnd = part.indexOf("\r\n\r\n");
            if (headerEnd < 0) continue;
            Matcher matcher = NAME.matcher(part.substring(0, headerEnd));
            if (!matcher.find()) continue;
            String content = part.substring(headerEnd + 4);
            while (content.endsWith("\r\n")) content = content.substring(0, content.length() - 2);
            values.put(matcher.group(1), new String(content.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
        }
        return values;
    }

    private static Map<String, String> urlEncoded(String body) throws Exception {
        Map<String, String> values = new LinkedHashMap<String, String>();
        if (body.isEmpty()) return values;
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            values.put(URLDecoder.decode(kv[0], "UTF-8"),
                    URLDecoder.decode(kv.length == 2 ? kv[1] : "", "UTF-8"));
        }
        return values;
    }

    private static String boundary(String contentType) {
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("boundary=")) {
                String value = trimmed.substring("boundary=".length());
                return value.startsWith("\"") && value.endsWith("\"") ? value.substring(1, value.length() - 1) : value;
            }
        }
        throw new IllegalArgumentException("multipart boundary is missing");
    }

    private static byte[] readLimited(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0, read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > limit) throw new IllegalArgumentException("request body is too large");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
