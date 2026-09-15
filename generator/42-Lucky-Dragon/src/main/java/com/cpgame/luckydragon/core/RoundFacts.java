package com.cpgame.luckydragon.core;

import java.math.BigDecimal;
import java.util.List;

/** Redis member 中保存的不可重算完整 Round 事实。派彩、Outcome 和余额均不落在 member 中。 */
public record RoundFacts(BigDecimal betSize, int betLevel, List<String> symbols, int reelMultiplier,
                         String roundKey, int deliveryIndex, boolean terminal) {
    public RoundFacts(BigDecimal betSize, int betLevel, List<String> symbols, int reelMultiplier,
                      String roundKey) {
        this(betSize, betLevel, symbols, reelMultiplier, roundKey, 0, true);
    }

    public RoundFacts {
        if (betSize == null || betSize.signum() <= 0) throw new IllegalArgumentException("betSize");
        if (betLevel < 1 || betLevel > 10) throw new IllegalArgumentException("betLevel");
        symbols = List.copyOf(symbols);
        if (symbols.size() != 3) throw new IllegalArgumentException("three symbols required");
        if (reelMultiplier != 0 && reelMultiplier != 3 && reelMultiplier != 5 && reelMultiplier != 9) {
            throw new IllegalArgumentException("invalid rpx");
        }
        if (deliveryIndex < 0) throw new IllegalArgumentException("invalid delivery index");
        if (roundKey == null || roundKey.isBlank() || roundKey.length() > 120 || roundKey.indexOf('|') >= 0) {
            throw new IllegalArgumentException("invalid round key");
        }
    }

    public static RoundFacts from(GameRound round) {
        return new RoundFacts(round.request().betSize(), round.request().betLevel(),
            round.result().symbols(), round.result().reelMultiplier(), round.roundKey(),
            round.deliveryIndex(), round.terminal());
    }
}
