package com.cpgame.luckywheel.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/** 从付费开始一次性组装当前已确认分支的完整 Round。 */
public final class CompleteRoundFactory {
    public GameRound create(RoundRequest request, RoundFacts facts, String roundKey) {
        if (request.betProfile() != facts.betProfile()) {
            throw new IllegalArgumentException("缓存完整局与请求下注门槛不匹配");
        }
        Instant now = Instant.now();
        BigDecimal baseAward = PayoutRules.score(facts.baseSymbols());
        BigDecimal featureAward;
        BigDecimal totalAward;
        String feature;
        switch (facts.mode()) {
            case 0 -> {
                feature = "H0";
                featureAward = BigDecimal.ZERO;
                totalAward = baseAward;
            }
            case 1 -> {
                feature = Integer.toString(facts.multiplier());
                featureAward = BigDecimal.ZERO;
                totalAward = baseAward.multiply(BigDecimal.valueOf(facts.multiplier()));
            }
            case 2 -> {
                feature = "RS";
                featureAward = PayoutRules.score(facts.respinSymbols());
                totalAward = baseAward.add(featureAward);
            }
            case 3 -> {
                feature = "SCAT";
                featureAward = BigDecimal.valueOf(facts.luckyWheelAward());
                totalAward = baseAward.add(featureAward);
            }
            default -> throw new UnsupportedOperationException("未启用或无证据模式: md=" + facts.mode());
        }

        BigDecimal bet = BigDecimal.valueOf((long) request.betLevel() * request.betSize());
        BigDecimal settled = request.balanceBefore().subtract(bet).add(totalAward).setScale(2, RoundingMode.HALF_UP);
        List<String> nonBlank = facts.baseSymbols().stream().filter(symbol -> !"H0".equals(symbol)).toList();
        SpinResult result = new SpinResult(
                bet, request.betLevel(), request.betSize(), now.getEpochSecond(), feature, featureAward,
                facts.mode() == 3 ? List.of(Integer.toString(facts.luckyWheelAward())) : facts.respinSymbols(),
                feature, 1, facts.mode(), settled.toPlainString(), facts.multiplier(),
                facts.baseSymbols(), facts.mode() == 3 ? 2 : 0, totalAward, nonBlank
        );
        OutcomeType outcome = switch (facts.mode()) {
            case 0 -> baseAward.signum() == 0 ? OutcomeType.ORDINARY_LOSS : OutcomeType.ORDINARY_WIN;
            case 1 -> OutcomeType.MULTIPLIER_MD1;
            case 2 -> OutcomeType.RESPIN_MD2;
            case 3 -> OutcomeType.SCATTER_LUCKY_WHEEL_MD3;
            default -> throw new UnsupportedOperationException("未启用或无证据模式: md=" + facts.mode());
        };
        GameRound round = new GameRound(roundKey, outcome, now, List.of(new RoundDelivery(0, result)));
        IndependentRoundVerifier.verify(round, request.balanceBefore());
        return round;
    }
}
