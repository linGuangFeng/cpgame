package com.cpgame.crazypiggy.generator.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 当前 Java 消费者恢复完整局所需的最小事实。
 * 赔付线、奖金、模式和 Delivery 均可由 GameRuleCore 重算，故不保存。
 */
public record RoundFacts(
        String roundKey,
        long createdAtEpochSecond,
        BigDecimal betSize,
        int betLevel,
        List<String> symbols,
        List<Integer> wheelPositions,
        List<Integer> wheelMultipliers) {

    public RoundFacts {
        symbols = List.copyOf(symbols);
        wheelPositions = List.copyOf(wheelPositions);
        wheelMultipliers = List.copyOf(wheelMultipliers);
    }
}
