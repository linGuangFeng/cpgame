package com.cpgame.luckydragon.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * 独立复算每个完整 Round，不调用 GameRuleCore.evaluate，也不信任生成器填写的派彩或类型。
 * 这里的断言由冻结 config 奖表与 WILD_X3/X5/X9 原始样本直接建立。
 */
public final class IndependentRoundVerifier {
    private final GameRuleCore rules;

    public IndependentRoundVerifier(GameRuleCore rules) { this.rules = Objects.requireNonNull(rules, "rules"); }

    public void verify(RoundRequest request, SpinResult candidate) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(candidate, "candidate");
        List<String> symbols = candidate.symbols();
        if (symbols.size() != 3) throw new IllegalArgumentException("independent oracle requires three symbols");
        for (String symbol : symbols) if (!GameRuleCore.SYMBOL_PAY_MULTIPLIERS.containsKey(symbol)) {
            throw new IllegalArgumentException("independent oracle found unknown symbol: " + symbol);
        }
        int rpx = candidate.reelMultiplier();
        if ("WILD".equals(symbols.get(1))) {
            if (rpx != 3 && rpx != 5 && rpx != 9) throw new IllegalArgumentException("center WILD multiplier mismatch");
        } else if (rpx != 0) throw new IllegalArgumentException("non-center-WILD has multiplier");

        String winning = independentWinningSymbol(symbols);
        int payMultiplier = winning.isEmpty() ? 0 : GameRuleCore.SYMBOL_PAY_MULTIPLIERS.get(winning);
        int appliedRpx = rpx == 0 ? 1 : rpx;
        BigDecimal expectedPayout = request.paidBet().multiply(BigDecimal.valueOf(payMultiplier))
            .multiply(BigDecimal.valueOf(appliedRpx)).setScale(2, RoundingMode.HALF_UP);
        OutcomeType expectedOutcome = expectedPayout.signum() == 0 ? OutcomeType.LOSS : switch (rpx) {
            case 3 -> OutcomeType.WILD_MULTIPLIER_X3;
            case 5 -> OutcomeType.WILD_MULTIPLIER_X5;
            case 9 -> OutcomeType.WILD_MULTIPLIER_X9;
            default -> OutcomeType.WIN;
        };
        String expectedWinning = expectedPayout.signum() == 0 ? "" : winning;
        if (!expectedWinning.equals(candidate.winningSymbol())
            || expectedPayout.compareTo(candidate.payout()) != 0
            || expectedOutcome != candidate.outcome()) {
            throw new IllegalArgumentException("candidate differs from frozen-evidence independent oracle");
        }
    }

    public void verify(GameRound round) {
        verify(round.request(), round.result());
        if (round.deliveryIndex() != 0 || !round.terminal()) {
            throw new IllegalArgumentException("gid42 complete round must be one terminal delivery");
        }
    }

    private static String independentWinningSymbol(List<String> symbols) {
        String candidate = symbols.stream().filter(symbol -> !"WILD".equals(symbol))
            .findFirst().orElse("WILD");
        return symbols.stream().allMatch(symbol -> "WILD".equals(symbol) || candidate.equals(symbol))
            ? candidate : "";
    }
}
