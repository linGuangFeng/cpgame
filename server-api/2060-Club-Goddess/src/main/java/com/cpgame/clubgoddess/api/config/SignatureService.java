package com.cpgame.clubgoddess.api.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

@Component
public class SignatureService {
    private static final String FRONTEND_SECRET = "3fZ8kL2qW9xA4pT7vJ1rQ6yB0sN5mX8h";

    public String sign(Map<String, String[]> source, String expire) {
        Map<String, String[]> sorted = new TreeMap<>(source);
        sorted.remove("signapt"); sorted.remove("expire");
        String canonical = sorted.entrySet().stream()
                .map(e -> e.getKey() + "=" + render(e.getValue()))
                .reduce((a, b) -> a + ":" + b).orElse("");
        return md5(FRONTEND_SECRET + expire + canonical + "aptsignature");
    }

    private String render(String[] values) {
        if (values == null || values.length == 0) return "";
        return values.length == 1 ? values[0] : Arrays.toString(values);
    }

    private String md5(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(32);
            for (byte b : digest) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
