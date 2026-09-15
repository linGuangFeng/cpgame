package com.cpgame.crazy777.generator;

import com.cpgame.crazy777.generator.model.RoundFacts;
import com.cpgame.crazy777.generator.model.RoundResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 一个极简 US-ASCII member 保存一整个 Round 的不可重算牌面事实。 */
public final class MinimalRoundFactCodec {
    public static final String PREFIX = "C777A1";
    private final RoundFactory roundFactory;
    private final RoundVerifier verifier;

    public MinimalRoundFactCodec(RoundFactory roundFactory, RoundVerifier verifier) {
        this.roundFactory = roundFactory;
        this.verifier = verifier;
    }

    public RoundFacts extract(RoundResult round) {
        verifier.verify(round);
        return new RoundFacts(round.roundKey(), round.bs(), round.bl(), round.boards());
    }

    public String encodeRedisMemberString(RoundResult round) {
        verifier.verify(round);
        List<String> boards = new ArrayList<>();
        for (var board : round.boards()) boards.add(String.join(",", board));
        return String.join(";", PREFIX, GameRules.RULES_HASH, String.join("|", boards));
    }

    public byte[] encodeRedisMember(RoundFacts facts) {
        return encodeRedisMemberString(roundFactory.restore(facts)).getBytes(StandardCharsets.US_ASCII);
    }

    public RoundResult decodeRedisMember(String payload) {
        return decodeRedisMember(payload, new BigDecimal("0.5"), 1,
                GameRules.betAmount(1, new BigDecimal("0.5")).multiply(BigDecimal.TEN));
    }

    public List<List<String>> decodeBoards(String payload) {
        if (payload == null || !StandardCharsets.US_ASCII.newEncoder().canEncode(payload)) {
            throw new IllegalArgumentException("member 必须是 US-ASCII");
        }
        if (payload.startsWith("{") || payload.startsWith("[")) {
            throw new IllegalArgumentException("member 禁止 JSON");
        }
        String[] f = payload.split(";", -1);
        if (f.length != 3 || !PREFIX.equals(f[0])) throw new IllegalArgumentException("member 格式错误");
        if (!GameRules.RULES_HASH.equals(f[1])) throw new IllegalArgumentException("rulesHash 不匹配");
        String[] rawBoards = f[2].split("\\|", -1);
        List<List<String>> boards = new ArrayList<>(rawBoards.length);
        for (String raw : rawBoards) boards.add(List.of(raw.split(",", -1)));
        return boards;
    }

    public RoundResult decodeRedisMember(String payload, BigDecimal bs, int bl, BigDecimal startingBalance) {
        return decodeRedisMember(payload, Long.toUnsignedString((long) payload.hashCode() & 0xffffffffL, 16),
                bs, bl, startingBalance);
    }

    public RoundResult decodeRedisMember(String payload, String roundKey, BigDecimal bs, int bl,
                                         BigDecimal startingBalance) {
        try {
            List<List<String>> boards = decodeBoards(payload);
            RoundFacts facts = new RoundFacts(roundKey, bs, bl, boards);
            RoundResult restored = roundFactory.restore(facts, startingBalance);
            verifier.verify(restored);
            return restored;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("完整 Round ASCII member 解码或复核失败", ex);
        }
    }
}
