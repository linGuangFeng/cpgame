package com.cpgame.crazy777.generator;

import com.cpgame.crazy777.generator.model.RoundFacts;
import com.cpgame.crazy777.generator.model.RoundMode;
import com.cpgame.crazy777.generator.model.RoundResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 一个极简 US-ASCII member 保存一整个 Round 的不可重算牌面事实。 */
public final class MinimalRoundFactCodec {
    public static final String PREFIX = "C72";
    private static final String LEGACY_PREFIX = "C777A1";
    private static final GameRuleCore LOSS_CORE = new GameRuleCore();
    private static final Map<String, Character> SYMBOL_TO_CODE = Map.of(
            "H1", '0', "H2", '1', "H3", '2', "H4", '3', "H5", '4',
            "H6", '5', "WILD", '6', "SC", '7', "BLANK", '8');
    private static final List<String> CODE_TO_SYMBOL =
            List.of("H1", "H2", "H3", "H4", "H5", "H6", "WILD", "SC", "BLANK");
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
        if (round.mode() == RoundMode.ORDINARY_LOSS && round.steps().size() == 1
                && round.totalWin().signum() == 0 && !ResultUtil.isScatterTrigger(round.boards().get(0))) return "#";
        StringBuilder member = new StringBuilder(PREFIX).append('|');
        for (int page = 0; page < round.boards().size(); page++) {
            if (page > 0) member.append('.');
            List<String> board = round.boards().get(page);
            if (board.size() != GameRules.BOARD_SIZE) throw new IllegalArgumentException("牌面格数错误");
            for (String symbol : board) {
                Character code = SYMBOL_TO_CODE.get(symbol);
                if (code == null) throw new IllegalArgumentException("非法符号: " + symbol);
                member.append(code);
            }
        }
        return member.toString();
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
        if (payload.equals("#")) {
            BigDecimal bs = new BigDecimal("0.5");
            BigDecimal start = GameRules.betAmount(1, bs).multiply(BigDecimal.TEN);
            return LOSS_CORE.generateIndependentLoss(1, bs, start).boards();
        }
        if (payload.startsWith(PREFIX + "|")) return decodeCompact(payload.substring(PREFIX.length() + 1));
        String[] f = payload.split(";", -1);
        if (f.length != 3 || !LEGACY_PREFIX.equals(f[0])) throw new IllegalArgumentException("member 格式错误");
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
            List<List<String>> boards;
            if ("#".equals(payload)) {
                RoundResult generated = LOSS_CORE.generateIndependentLoss(bl, bs, startingBalance);
                boards = generated.boards();
            } else {
                boards = decodeBoards(payload);
            }
            RoundFacts facts = new RoundFacts(roundKey, bs, bl, boards);
            RoundResult restored = roundFactory.restore(facts, startingBalance);
            verifier.verify(restored);
            return restored;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("完整 Round ASCII member 解码或复核失败", ex);
        }
    }

    private static List<List<String>> decodeCompact(String payload) {
        String[] pages = payload.split("\\.", -1);
        List<List<String>> boards = new ArrayList<>(pages.length);
        for (String page : pages) {
            if (page.length() != GameRules.BOARD_SIZE) throw new IllegalArgumentException("牌面格数错误");
            List<String> board = new ArrayList<>(GameRules.BOARD_SIZE);
            for (int i = 0; i < page.length(); i++) {
                int code = page.charAt(i) - '0';
                if (code < 0 || code >= CODE_TO_SYMBOL.size()) throw new IllegalArgumentException("符号编码错误");
                board.add(CODE_TO_SYMBOL.get(code));
            }
            boards.add(List.copyOf(board));
        }
        return List.copyOf(boards);
    }
}
