package com.cpgame.luckydragon.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 唯一正式规则核心。Controller 投影、独立复核器与 Redis Loader 都调用本类。
 * 规则证据来自 gid42 冻结 config 与 1131 组 Spin/History 1:1 样本，不读取历史响应作为运行时结果。
 */
public final class GameRuleCore {
    public static final int GAME_ID = 42;
    public static final String GAME_NAME = "Lucky Dragon";
    public static final String RULES_HASH = "b80c2bc3d8e9d04305c2808a59843743a3950241842c2dc70b36e1e0012b1a57";
    public static final List<BigDecimal> BET_SIZES = List.of(
        new BigDecimal("0.5"), new BigDecimal("5"), new BigDecimal("20"));
    public static final List<Integer> BET_LEVELS = List.of(1,2,3,4,5,6,7,8,9,10);
    public static final List<Integer> AUTO_SPINS = List.of(10,30,50,100,500);
    public static final Map<String,Integer> SYMBOL_PAY_MULTIPLIERS = payTable();

    private static Map<String,Integer> payTable() {
        Map<String,Integer> values = new LinkedHashMap<>();
        values.put("H0", 0);
        values.put("H1", 111);
        values.put("H2", 21);
        values.put("H3", 5);
        values.put("H4", 1);
        values.put("WILD", 1111);
        return Map.copyOf(values);
    }

    public SpinResult evaluate(RoundRequest request, List<String> symbols, int reelMultiplier) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(symbols, "symbols");
        if (symbols.size() != 3) throw new IllegalArgumentException("exactly three symbols required");
        for (String symbol : symbols) {
            if (!SYMBOL_PAY_MULTIPLIERS.containsKey(symbol)) throw new IllegalArgumentException("unknown symbol: " + symbol);
        }
        if ("WILD".equals(symbols.get(1))) {
            if (reelMultiplier != 3 && reelMultiplier != 5 && reelMultiplier != 9) {
                throw new IllegalArgumentException("center WILD requires multiplier 3, 5 or 9");
            }
        } else if (reelMultiplier != 0) {
            throw new IllegalArgumentException("non-center-WILD result must use multiplier 0");
        }

        String winning = resolveWinningSymbol(symbols);
        if (winning.isEmpty()) return new SpinResult(symbols, "", reelMultiplier, BigDecimal.ZERO, OutcomeType.LOSS);
        int baseMultiplier = SYMBOL_PAY_MULTIPLIERS.get(winning);
        if (baseMultiplier == 0) return new SpinResult(symbols, "", reelMultiplier, BigDecimal.ZERO, OutcomeType.LOSS);
        int appliedMultiplier = reelMultiplier == 0 ? 1 : reelMultiplier;
        BigDecimal payout = request.paidBet()
            .multiply(BigDecimal.valueOf(baseMultiplier))
            .multiply(BigDecimal.valueOf(appliedMultiplier))
            .setScale(2, RoundingMode.HALF_UP);
        OutcomeType outcome = switch (reelMultiplier) {
            case 3 -> OutcomeType.WILD_MULTIPLIER_X3;
            case 5 -> OutcomeType.WILD_MULTIPLIER_X5;
            case 9 -> OutcomeType.WILD_MULTIPLIER_X9;
            default -> OutcomeType.WIN;
        };
        return new SpinResult(symbols, winning, reelMultiplier, payout, outcome);
    }

    private String resolveWinningSymbol(List<String> symbols) {
        String candidate = symbols.stream().filter(symbol -> !"WILD".equals(symbol)).findFirst().orElse("WILD");
        boolean matches = symbols.stream().allMatch(symbol -> "WILD".equals(symbol) || symbol.equals(candidate));
        return matches ? candidate : "";
    }
}
