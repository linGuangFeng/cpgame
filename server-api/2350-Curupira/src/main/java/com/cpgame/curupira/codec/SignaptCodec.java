package com.cpgame.curupira.codec;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Component
public final class SignaptCodec {
    static final String SECRET = "3fZ8kL2qW9xA4pT7vJ1rQ6yB0sN5mX8h";

    public String calculate(Map<String, String> unsignedParameters, String expire) {
        TreeMap<String, String> sorted = new TreeMap<>(unsignedParameters);
        sorted.remove("signapt");
        sorted.remove("expire");
        String canonical = sorted.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(":"));
        String input = SECRET + expire + canonical + "aptsignature";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 must be available in the Java runtime", e);
        }
    }

    public boolean matches(Map<String, String> parameters) {
        String expire = parameters.get("expire");
        String supplied = parameters.get("signapt");
        return expire != null && supplied != null && supplied.equals(calculate(parameters, expire));
    }
}
