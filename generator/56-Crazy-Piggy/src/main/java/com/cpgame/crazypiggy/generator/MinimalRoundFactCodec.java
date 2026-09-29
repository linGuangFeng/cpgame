package com.cpgame.crazypiggy.generator;

import com.cpgame.crazypiggy.generator.model.RoundFacts;
import com.cpgame.crazypiggy.generator.model.RoundResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 一个极简 US-ASCII member 保存一整个 Round 的不可重算事实。 */
public final class MinimalRoundFactCodec {
    public static final String PREFIX = "CP2";
    private static final String LEGACY_PREFIX = "CP56A1";
    private static final BigDecimal BASE_BET_SIZE = new BigDecimal("0.5");
    private static final GameRuleCore LOSS_CORE = new GameRuleCore();
    private static final Map<String, Character> SYMBOL_TO_CODE = Map.of(
            "HOT", '0', "SEV", '1', "H2", '2', "H3", '3',
            "H4", '4', "H5", '5', "H6", '6', "H7", '7');
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
        if (round.loss() && !round.boosterWheel() && round.wheelPositions().isEmpty()
                && round.wheelMultipliers().isEmpty() && round.deliveries().isEmpty()) return "#";
        StringBuilder symbols = new StringBuilder(9);
        for (String symbol : round.symbols()) {
            Character code = SYMBOL_TO_CODE.get(symbol);
            if (code == null) throw new IllegalArgumentException("非法符号: " + symbol);
            symbols.append(code);
        }
        return PREFIX + '|' + symbols + '|' + digits(round.wheelPositions()) + '|' + digits(round.wheelMultipliers());
    }

    public RoundResult decodeRedisMember(String payload) {
        try {
            if (payload == null || !StandardCharsets.US_ASCII.newEncoder().canEncode(payload))
                throw new IllegalArgumentException("member 必须是 US-ASCII");
            if (payload.equals("#")) return LOSS_CORE.generateIndependentLoss(BASE_BET_SIZE, 1);
            if (payload.startsWith(PREFIX + "|")) return decodeCompact(payload);
            String[] f = payload.split(";", -1);
            if (f.length != 9 || !LEGACY_PREFIX.equals(f[0])) throw new IllegalArgumentException("member 格式错误");
            if (!GameRules.RULES_HASH.equals(f[1])) throw new IllegalArgumentException("rulesHash 不匹配");
            List<String> symbols = List.of(f[6].split(",", -1));
            RoundFacts facts = new RoundFacts(f[2], Long.parseLong(f[3]), new BigDecimal(f[4]),
                    Integer.parseInt(f[5]), symbols, ints(f[7]), ints(f[8]));
            return restore(facts);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("完整 Round ASCII member 解码或复核失败", ex);
        }
    }

    private RoundResult decodeCompact(String payload) {
        String[] fields = payload.split("\\|", -1);
        if (fields.length != 4 || !PREFIX.equals(fields[0]) || fields[1].length() != 9)
            throw new IllegalArgumentException("CP2 member 格式错误");
        List<String> symbols = new ArrayList<>(9);
        for (int i = 0; i < fields[1].length(); i++) {
            int code = fields[1].charAt(i) - '0';
            if (code < 0 || code >= GameRules.SYMBOLS.size()) throw new IllegalArgumentException("符号编码错误");
            symbols.add(GameRules.SYMBOLS.get(code));
        }
        RoundFacts facts = new RoundFacts(runtimeRoundKey(), Instant.now().getEpochSecond(), BASE_BET_SIZE, 1,
                symbols, compactInts(fields[2], 0, 7), compactInts(fields[3], 1, 2));
        return restore(facts);
    }

    private static String digits(List<Integer> values) {
        StringBuilder result = new StringBuilder(values.size());
        for (int value : values) {
            if (value < 0 || value > 9) throw new IllegalArgumentException("单字符整数超出范围");
            result.append((char) ('0' + value));
        }
        return result.toString();
    }

    private static List<Integer> compactInts(String value, int min, int max) {
        List<Integer> result = new ArrayList<>(value.length());
        for (int i = 0; i < value.length(); i++) {
            int item = value.charAt(i) - '0';
            if (item < min || item > max) throw new IllegalArgumentException("整数编码错误");
            result.add(item);
        }
        return List.copyOf(result);
    }

    private static List<Integer> ints(String value) {
        if (value.isEmpty()) return List.of();
        List<Integer> result = new ArrayList<>();
        for (String item : value.split(",")) result.add(Integer.parseInt(item));
        return List.copyOf(result);
    }

    private static String runtimeRoundKey() {
        return "cp56-redis-" + UUID.randomUUID().toString().replace("-", "");
    }
}
