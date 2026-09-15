package com.cpgame.crazypiggy.generator;

import com.cpgame.crazypiggy.generator.model.RoundFacts;
import com.cpgame.crazypiggy.generator.model.RoundResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 一个极简 US-ASCII member 保存一整个 Round 的不可重算事实。 */
public final class MinimalRoundFactCodec {
    public static final String PREFIX = "CP56A1";
    private final RoundFactory roundFactory;
    private final RoundVerifier verifier;

    public MinimalRoundFactCodec(RoundFactory roundFactory, RoundVerifier verifier) {
        this.roundFactory = roundFactory;
        this.verifier = verifier;
    }

    public RoundFacts extract(RoundResult round) {
        verifier.verify(round);
        return new RoundFacts(round.roundKey(), round.createdAtEpochSecond(), round.betSize(), round.betLevel(),
                round.symbols(), round.wheelPositions(), round.wheelMultipliers());
    }

    public RoundResult restore(RoundFacts facts) {
        RoundResult restored = roundFactory.restore(facts);
        verifier.verify(restored);
        return restored;
    }

    public byte[] encodeRedisMember(RoundFacts facts) {
        return encodeRedisMemberString(restore(facts)).getBytes(StandardCharsets.US_ASCII);
    }

    public String encodeRedisMemberString(RoundResult round) {
        verifier.verify(round);
        requireAsciiToken(round.roundKey(), "roundKey");
        return String.join(";", PREFIX, GameRules.RULES_HASH, round.roundKey(),
                Long.toString(round.createdAtEpochSecond()), round.betSize().toPlainString(),
                Integer.toString(round.betLevel()), String.join(",", round.symbols()),
                joinInts(round.wheelPositions()), joinInts(round.wheelMultipliers()));
    }

    public RoundResult decodeRedisMember(String payload) {
        try {
            if (payload == null || !StandardCharsets.US_ASCII.newEncoder().canEncode(payload))
                throw new IllegalArgumentException("member 必须是 US-ASCII");
            String[] f = payload.split(";", -1);
            if (f.length != 9 || !PREFIX.equals(f[0])) throw new IllegalArgumentException("member 格式错误");
            if (!GameRules.RULES_HASH.equals(f[1])) throw new IllegalArgumentException("rulesHash 不匹配");
            List<String> symbols = List.of(f[6].split(",", -1));
            RoundFacts facts = new RoundFacts(f[2], Long.parseLong(f[3]), new BigDecimal(f[4]),
                    Integer.parseInt(f[5]), symbols, ints(f[7]), ints(f[8]));
            return restore(facts);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("完整 Round ASCII member 解码或复核失败", ex);
        }
    }

    private static String joinInts(List<Integer> values) {
        return values.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    private static List<Integer> ints(String value) {
        if (value.isEmpty()) return List.of();
        List<Integer> result = new ArrayList<>();
        for (String item : value.split(",")) result.add(Integer.parseInt(item));
        return List.copyOf(result);
    }

    private static void requireAsciiToken(String value, String field) {
        if (value == null || value.isBlank() || value.indexOf(';') >= 0 || value.indexOf(',') >= 0
                || !StandardCharsets.US_ASCII.newEncoder().canEncode(value))
            throw new IllegalArgumentException(field + " 不是安全 ASCII token");
    }
}
