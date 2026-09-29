package com.cpgame.crazybirds.generator;

import com.cpgame.crazybirds.generator.model.RoundResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Redis member 只保存完整局事实：每个 Step 24 个单字符，Step 之间仅用 | 分隔。 */
public final class MinimalRoundFactCodec {
    public static final String ALPHABET = "0123456789ABCDEF";
    private static final Map<String, Character> ENCODE = encodeTable();
    private final RoundFactory factory;
    private final RoundVerifier verifier;

    public MinimalRoundFactCodec(RoundFactory factory, RoundVerifier verifier) {
        this.factory = factory;
        this.verifier = verifier;
    }

    public String encodeRedisMemberString(RoundResult round) {
        verifier.verify(round);
        List<String> boards = new ArrayList<>();
        for (var board : round.boards()) {
            StringBuilder compact = new StringBuilder(GameRules.BOARD_SIZE);
            for (String symbol : board) {
                Character code = ENCODE.get(symbol);
                if (code == null) throw new IllegalArgumentException("没有紧凑编码的符号: " + symbol);
                compact.append(code);
            }
            boards.add(compact.toString());
        }
        return String.join("|", boards);
    }

    public List<List<String>> decodeBoards(String payload) {
        if (payload == null || !StandardCharsets.US_ASCII.newEncoder().canEncode(payload)) {
            throw new IllegalArgumentException("member 必须是 US-ASCII");
        }
        if (payload.startsWith("{") || payload.startsWith("[")) {
            throw new IllegalArgumentException("member 禁止 JSON");
        }
        if (payload.isEmpty() || payload.indexOf(';') >= 0) {
            throw new IllegalArgumentException("member 只能包含紧凑牌面与 Step 分隔符");
        }
        String[] rawBoards = payload.split("\\|", -1);
        List<List<String>> boards = new ArrayList<>(rawBoards.length);
        for (String raw : rawBoards) {
            if (raw.length() != GameRules.BOARD_SIZE) {
                throw new IllegalArgumentException("紧凑牌面长度必须为 " + GameRules.BOARD_SIZE);
            }
            List<String> board = new ArrayList<>(GameRules.BOARD_SIZE);
            for (int i = 0; i < raw.length(); i++) {
                int index = ALPHABET.indexOf(raw.charAt(i));
                if (index < 0 || index >= GameRules.ALL_SYMBOLS.size()) {
                    throw new IllegalArgumentException("未知紧凑符号: " + raw.charAt(i));
                }
                board.add(GameRules.ALL_SYMBOLS.get(index));
            }
            boards.add(List.copyOf(board));
        }
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

    private static Map<String, Character> encodeTable() {
        if (ALPHABET.length() != GameRules.ALL_SYMBOLS.size()) {
            throw new ExceptionInInitializerError("符号表与紧凑字母表长度不一致");
        }
        Map<String, Character> table = new HashMap<>();
        for (int i = 0; i < ALPHABET.length(); i++) {
            table.put(GameRules.ALL_SYMBOLS.get(i), ALPHABET.charAt(i));
        }
        return Map.copyOf(table);
    }
}
