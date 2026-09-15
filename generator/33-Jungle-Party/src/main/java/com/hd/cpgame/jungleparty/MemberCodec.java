package com.hd.cpgame.jungleparty;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Versioned JSON envelope containing one complete generated Round. */
public final class MemberCodec {
    private static final String PREFIX = "{\"schemaVersion\":1,\"rawGameId\":33,\"rulesHash\":\"" + GameRuleCore.RULES_HASH + "\",\"payload\":\"";
    private MemberCodec() {}
    public static String encode(GameRuleCore.Round round) {
        IndependentVerifier.Verification result = IndependentVerifier.verify(round);
        if (!result.pass()) throw new IllegalArgumentException("cannot encode invalid Round: " + result.errors());
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(round); }
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray()) + "\"}";
        } catch (Exception exception) { throw new IllegalStateException("encode failed", exception); }
    }
    public static GameRuleCore.Round decode(String member) {
        if (member == null || !member.startsWith(PREFIX) || !member.endsWith("\"}")) throw new IllegalArgumentException("invalid gid33 member envelope");
        String payload = member.substring(PREFIX.length(), member.length() - 2);
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(payload.getBytes(StandardCharsets.US_ASCII));
            Object value;
            try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes))) { value = input.readObject(); }
            if (!(value instanceof GameRuleCore.Round round)) throw new IllegalArgumentException("payload is not a Round");
            IndependentVerifier.Verification result = IndependentVerifier.verify(round);
            if (!result.pass()) throw new IllegalArgumentException("decoded Round failed verification: " + result.errors());
            return round;
        } catch (IllegalArgumentException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalArgumentException("member decode failed", exception); }
    }
}
