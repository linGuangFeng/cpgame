package com.hd.cpgame.riocarnival.server.state;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class Hashing {
    private Hashing() {}
    static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("sha256:");
            for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
