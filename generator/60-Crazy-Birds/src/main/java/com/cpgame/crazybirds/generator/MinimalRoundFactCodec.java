package com.cpgame.crazybirds.generator;

import com.cpgame.crazybirds.generator.model.RoundResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** CB60A1;rulesHash;board,board|board,board */
public final class MinimalRoundFactCodec {
    public static final String PREFIX = "CB60A1";
    private final RoundFactory factory;
    private final RoundVerifier verifier;

    public MinimalRoundFactCodec(RoundFactory factory, RoundVerifier verifier) {
        this.factory = factory;
        this.verifier = verifier;
    }

    public String encodeRedisMemberString(RoundResult round) {
        verifier.verify(round);
        List<String> boards = new ArrayList<>();
        for (var board : round.boards()) boards.add(String.join(",", board));
        return String.join(";", PREFIX, GameRules.RULES_HASH, String.join("|", boards));
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
        return decodeRedisMember(payload,
                Long.toUnsignedString((long) payload.hashCode() & 0xffffffffL, 16), bs, bl, startingBalance);
    }

    public RoundResult decodeRedisMember(String payload, String roundKey, BigDecimal bs, int bl,
                                         BigDecimal startingBalance) {
        List<List<String>> boards = decodeBoards(payload);
        RoundResult restored = factory.restore(roundKey, bs, bl, startingBalance, boards);
        verifier.verify(restored);
        return restored;
    }
}
