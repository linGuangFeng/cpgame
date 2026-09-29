package com.cpgame.luckycatii;

import com.cpgame.luckycatii.model.RoundFacts;
import com.cpgame.luckycatii.model.RoundMode;
import com.cpgame.luckycatii.model.RoundResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Compact US-ASCII facts. Boards use one-character symbol codes and independent losses use {@code #}. */
public final class MinimalRoundFactCodec {
    public static final String PREFIX = "LC2|";
    private static final String LEGACY_PREFIX = "LC50A1";
    private static final String CODES = "0123456";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final GameRuleCore LOSSES = new GameRuleCore();
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
        var analysis = verifier.verify(round);
        if (analysis.redisPoolMode() == RoundMode.ORDINARY_LOSS && round.steps().size() == 1
                && round.award().signum() == 0 && round.gameMode() == 0 && round.rpx() == 1
                && round.paidBoard().equals(round.finalBoard())) return "#";
        StringBuilder out = new StringBuilder(PREFIX)
                .append(Character.forDigit(round.rpx(), 36))
                .append(round.gameMode() == 1 ? 'R' : 'N');
        appendBoard(out, round.paidBoard());
        if (round.gameMode() == 1) appendBoard(out, round.finalBoard());
        return out.toString();
    }

    public RoundResult decodeRedisMember(String payload) {
        try {
            if (payload == null || payload.isEmpty() || !StandardCharsets.US_ASCII.newEncoder().canEncode(payload)) {
                throw new IllegalArgumentException("member 必须是极简 US-ASCII");
            }
            if ("#".equals(payload)) {
                return LOSSES.generateIndependentLoss(new BigDecimal("0.1"), 1);
            }
            if (payload.startsWith(PREFIX)) return decodeCompact(payload);
            return decodeLegacy(payload);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("完整 Round ASCII member 解码或复核失败", ex);
        }
    }

    private RoundResult decodeCompact(String payload) {
        int offset = PREFIX.length();
        if (payload.length() < offset + 2 + 9) throw new IllegalArgumentException("LC2 member 太短");
        int rpx = Character.digit(payload.charAt(offset), 36);
        boolean lucky = switch (payload.charAt(offset + 1)) {
            case 'R' -> true;
            case 'N' -> false;
            default -> throw new IllegalArgumentException("LC2 模式码错误");
        };
        int expected = offset + 2 + (lucky ? 18 : 9);
        if (payload.length() != expected) throw new IllegalArgumentException("LC2 member 长度错误");
        List<String> paid = decodeBoard(payload, offset + 2);
        List<String> finalBoard = lucky ? decodeBoard(payload, offset + 11) : paid;
        RoundFacts facts = new RoundFacts(roundKey(), Instant.now().getEpochSecond(), new BigDecimal("0.1"),
                1, paid, finalBoard, rpx, lucky);
        RoundResult restored = roundFactory.restore(facts);
        verifier.verify(restored);
        return restored;
    }

    /** Compatibility for existing LC50A1 members; new writes never include commas or metadata. */
    private RoundResult decodeLegacy(String payload) {
        if (payload.charAt(0) == '{' || payload.charAt(0) == '[') throw new IllegalArgumentException("member 禁止 JSON");
        String[] f = payload.split(";", -1);
        if (f.length != 9 || !LEGACY_PREFIX.equals(f[0])) throw new IllegalArgumentException("member 格式错误");
        if (!GameRules.RULES_HASH.equals(f[1])) throw new IllegalArgumentException("rulesHash 不匹配");
        List<String> s01 = List.of(f[7].split(",", -1));
        boolean lucky = !f[8].isEmpty();
        List<String> s02 = lucky ? List.of(f[8].split(",", -1)) : s01;
        RoundFacts facts = new RoundFacts(f[2], Long.parseLong(f[3]), new BigDecimal(f[4]),
                Integer.parseInt(f[5]), s01, s02, Integer.parseInt(f[6]), lucky);
        RoundResult restored = roundFactory.restore(facts);
        verifier.verify(restored);
        return restored;
    }

    private static void appendBoard(StringBuilder out, List<String> board) {
        if (board.size() != 9) throw new IllegalArgumentException("LC2 board 必须为9格");
        for (String symbol : board) {
            int index = GameRules.SYMBOLS.indexOf(symbol);
            if (index < 0) throw new IllegalArgumentException("未知符号: " + symbol);
            out.append(CODES.charAt(index));
        }
    }

    private static List<String> decodeBoard(String value, int offset) {
        List<String> board = new ArrayList<>(9);
        for (int cell = 0; cell < 9; cell++) {
            int index = CODES.indexOf(value.charAt(offset + cell));
            if (index < 0) throw new IllegalArgumentException("未知符号码");
            board.add(GameRules.SYMBOLS.get(index));
        }
        return List.copyOf(board);
    }

    private static String roundKey() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return "LC50-" + HexFormat.of().formatHex(bytes);
    }
}
