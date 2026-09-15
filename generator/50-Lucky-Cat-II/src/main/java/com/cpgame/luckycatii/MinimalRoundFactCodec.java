package com.cpgame.luckycatii;

import com.cpgame.luckycatii.model.RoundFacts;
import com.cpgame.luckycatii.model.RoundResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Compact US-ASCII member for one complete Round. Not JSON. */
public final class MinimalRoundFactCodec {
    public static final String PREFIX = "LC50A1";
    private final RoundFactory roundFactory;
    private final RoundVerifier verifier;

    public MinimalRoundFactCodec(RoundFactory roundFactory, RoundVerifier verifier) {
        this.roundFactory = roundFactory;
        this.verifier = verifier;
    }

    public RoundFacts extract(RoundResult round) {
        verifier.verify(round);
        return new RoundFacts(round.roundKey(), round.createdAtEpochSecond(), round.betSize(), round.betLevel(),
                round.paidBoard(), round.finalBoard(), round.rpx(), round.gameMode() == 1);
    }

    public String encodeRedisMemberString(RoundResult round) {
        verifier.verify(round);
        requireAsciiToken(round.roundKey(), "roundKey");
        String s02 = round.gameMode() == 1 ? String.join(",", round.finalBoard()) : "";
        return String.join(";", PREFIX, GameRules.RULES_HASH, round.roundKey(),
                Long.toString(round.createdAtEpochSecond()), round.betSize().toPlainString(),
                Integer.toString(round.betLevel()), Integer.toString(round.rpx()),
                String.join(",", round.paidBoard()), s02);
    }

    public RoundResult decodeRedisMember(String payload) {
        try {
            if (payload == null || payload.isEmpty() || payload.charAt(0) == '{' || payload.charAt(0) == '[') {
                throw new IllegalArgumentException("member 必须是极简 ASCII，禁止 JSON");
            }
            if (!StandardCharsets.US_ASCII.newEncoder().canEncode(payload)) {
                throw new IllegalArgumentException("member 必须是 US-ASCII");
            }
            String[] f = payload.split(";", -1);
            if (f.length != 9 || !PREFIX.equals(f[0])) throw new IllegalArgumentException("member 格式错误");
            if (!GameRules.RULES_HASH.equals(f[1])) throw new IllegalArgumentException("rulesHash 不匹配");
            List<String> s01 = List.of(f[7].split(",", -1));
            boolean lucky = !f[8].isEmpty();
            List<String> s02 = lucky ? List.of(f[8].split(",", -1)) : s01;
            RoundFacts facts = new RoundFacts(f[2], Long.parseLong(f[3]), new BigDecimal(f[4]),
                    Integer.parseInt(f[5]), s01, s02, Integer.parseInt(f[6]), lucky);
            RoundResult restored = roundFactory.restore(facts);
            verifier.verify(restored);
            return restored;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("完整 Round ASCII member 解码或复核失败", ex);
        }
    }
    private static void requireAsciiToken(String value, String field) {
        if (value == null || value.isBlank() || value.indexOf(';') >= 0 || value.indexOf(',') >= 0
                || !StandardCharsets.US_ASCII.newEncoder().canEncode(value)) {
            throw new IllegalArgumentException(field + " 不是安全 ASCII token");
        }
    }
}
