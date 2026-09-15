package com.cpgame.luckywheel.core;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 当前游戏的极简 Redis member：只保存不可重算事实，不保存派奖、结果类型或响应外壳。
 * 格式按 1809 的紧凑 ASCII member 思路适配 Lucky Wheel：
 * bet&lt;5 保留已交付格式；bet&gt;=5 用前缀5编码三个基础位置和同一Round的嵌入特性。
 */
public final class MinimalFactCodec {
    private static final List<Character> SYMBOL_CODES = List.of('0', '1', '3', '4', '5');

    public RoundFacts extract(GameRound round) {
        SpinResult result = round.deliveries().get(0).result();
        return switch (result.md()) {
            case 0 -> RoundFacts.ordinary(result.bl() < 5 ? 1 : 5, result.rskl());
            case 1 -> RoundFacts.multiplier(result.bl() < 5 ? 1 : 5, result.rskl(), result.rpx());
            case 2 -> RoundFacts.respin(result.bl() < 5 ? 1 : 5, result.rskl(), result.fwi());
            case 3 -> RoundFacts.luckyWheel(result.rskl(), result.fwa().intValueExact());
            default -> throw new UnsupportedOperationException("未启用或无证据模式: md=" + result.md());
        };
    }

    public byte[] encodeRedisMember(RoundFacts facts) {
        StringBuilder value = new StringBuilder(9);
        if (facts.betProfile() == 5) value.append('5');
        value.append(facts.mode());
        if (facts.mode() == 1) value.append(facts.multiplier());
        appendSymbols(value, facts.baseSymbols());
        if (facts.mode() == 2) appendSymbols(value, facts.respinSymbols());
        if (facts.mode() == 3) value.append(String.format(java.util.Locale.ROOT, "%03d", facts.luckyWheelAward()));
        return value.toString().getBytes(StandardCharsets.US_ASCII);
    }

    public RoundFacts decodeRedisMember(byte[] member) {
        if (member == null) throw new IllegalArgumentException("Redis member 不能为空");
        String value = new String(member, StandardCharsets.US_ASCII);
        if (!value.isEmpty() && value.charAt(0) == '5') return decodeUnlocked(value);
        return switch (value.length() == 0 ? -1 : value.charAt(0)) {
            case '0' -> {
                requireLength(value, 3);
                yield RoundFacts.ordinary(decodeSymbols(value, 1));
            }
            case '1' -> {
                requireLength(value, 4);
                int multiplier = Character.digit(value.charAt(1), 10);
                if (multiplier != 2 && multiplier != 5) throw new IllegalArgumentException("md=1 倍率码不合法");
                yield RoundFacts.multiplier(decodeSymbols(value, 2), multiplier);
            }
            case '2' -> {
                requireLength(value, 5);
                yield RoundFacts.respin(decodeSymbols(value, 1, 2), decodeSymbols(value, 3, 2));
            }
            default -> throw new IllegalArgumentException("未知 Redis member 模式码");
        };
    }

    private static RoundFacts decodeUnlocked(String value) {
        return switch (value.length() < 2 ? -1 : value.charAt(1)) {
            case '0' -> { requireLength(value, 5); yield RoundFacts.ordinary(5, decodeSymbols(value, 2, 3)); }
            case '1' -> {
                requireLength(value, 6);
                int multiplier = Character.digit(value.charAt(2), 10);
                if (multiplier != 2 && multiplier != 5) throw new IllegalArgumentException("md=1 倍率码不合法");
                yield RoundFacts.multiplier(5, decodeSymbols(value, 3, 3), multiplier);
            }
            case '2' -> { requireLength(value, 8); yield RoundFacts.respin(5, decodeSymbols(value, 2, 3), decodeSymbols(value, 5, 3)); }
            case '3' -> {
                requireLength(value, 8);
                int award;
                try { award = Integer.parseInt(value.substring(5)); }
                catch (NumberFormatException error) { throw new IllegalArgumentException("md=3 奖励码不合法", error); }
                yield RoundFacts.luckyWheel(decodeSymbols(value, 2, 3), award);
            }
            default -> throw new IllegalArgumentException("未知 bet>=5 Redis member 模式码");
        };
    }

    private static void appendSymbols(StringBuilder target, List<String> symbols) {
        for (String symbol : symbols) {
            if (symbol == null || symbol.length() != 2 || symbol.charAt(0) != 'H'
                    || !SYMBOL_CODES.contains(symbol.charAt(1))) {
                throw new IllegalArgumentException("符号无法编码: " + symbol);
            }
            target.append(symbol.charAt(1));
        }
    }

    private static List<String> decodeSymbols(String value, int offset) {
        return decodeSymbols(value, offset, 2);
    }

    private static List<String> decodeSymbols(String value, int offset, int count) {
        String[] symbols = new String[count];
        for (int i = 0; i < count; i++) {
            char code = value.charAt(offset + i);
            if (!SYMBOL_CODES.contains(code)) throw new IllegalArgumentException("未知符号码: " + code);
            symbols[i] = "H" + code;
        }
        return List.of(symbols);
    }

    private static void requireLength(String value, int expected) {
        if (value.length() != expected) throw new IllegalArgumentException("Redis member 长度不合法");
    }
}
