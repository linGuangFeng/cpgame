package com.cpgame.luckywheel.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 独立结果反推器；不调用生成侧 PayoutRules，也不接收生成侧期望 Outcome。 */
public final class ResultUtil {
    private static final List<String> ALLOWED = List.of("H0", "H1", "H3", "H4", "H5");

    private ResultUtil() { }

    public static ResultAnalysis analyze(RoundFacts facts) {
        require(facts.baseSymbols().stream().allMatch(ALLOWED::contains), "基础符号超出已确认集合");
        BigDecimal base = independentScore(facts.baseSymbols());
        return switch (facts.mode()) {
            case 0 -> new ResultAnalysis(
                    base.signum() == 0 ? OutcomeType.ORDINARY_LOSS : OutcomeType.ORDINARY_WIN,
                    base, BigDecimal.ZERO, base, false);
            case 1 -> new ResultAnalysis(
                    OutcomeType.MULTIPLIER_MD1, base, BigDecimal.ZERO,
                    base.multiply(BigDecimal.valueOf(facts.multiplier())), false);
            case 2 -> {
                require(facts.respinSymbols().stream().allMatch(ALLOWED::contains), "重转符号超出已确认集合");
                BigDecimal feature = independentScore(facts.respinSymbols());
                yield new ResultAnalysis(OutcomeType.RESPIN_MD2, base, feature, base.add(feature), false);
            }
            case 3 -> {
                require(facts.betProfile() == 5, "md=3 不得出现于 bet<5");
                BigDecimal feature = BigDecimal.valueOf(facts.luckyWheelAward());
                yield new ResultAnalysis(OutcomeType.SCATTER_LUCKY_WHEEL_MD3, base, feature,
                        base.add(feature), false);
            }
            default -> throw new UnsupportedOperationException("未启用或无证据模式: md=" + facts.mode());
        };
    }

    public static void assertValid(GameRound round, BigDecimal balanceBefore) {
        IndependentRoundVerifier.verify(round, balanceBefore);
    }

    public static BigDecimal independentScore(List<String> symbols) {
        List<String> texts = new ArrayList<>();
        for (String symbol : symbols) {
            switch (symbol) {
                case "H0" -> { }
                case "H1" -> texts.add("0");
                case "H2" -> texts.add("00");
                case "H3" -> texts.add("1");
                case "H4" -> texts.add("5");
                case "H5" -> texts.add("10");
                default -> throw new IllegalArgumentException("未知符号: " + symbol);
            }
        }
        String joined = String.join("", texts);
        return joined.isEmpty() ? BigDecimal.ZERO : new BigDecimal(joined);
    }

    static void require(boolean value, String message) {
        if (!value) throw new IllegalArgumentException(message);
    }
}
