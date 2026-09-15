package com.hd.cpgame.magicscroll2.core;

import java.security.SecureRandom;

/**
 * 非权威试玩概率策略。它只选择已由当前游戏证据确认的完整 Round 类型，
 * 不代表或声称复现原厂 RTP；权重必须由运行时外置配置显式提供。
 */
public final class TrialProbabilityPolicy {
    private final int lossWeight;
    private final int baseWinWeight;
    private final int xSplitWeight;
    private final int xBombWildWeight;
    private final int totalWeight;

    public TrialProbabilityPolicy(int lossWeight, int baseWinWeight,
                                  int xSplitWeight, int xBombWildWeight) {
        this.lossWeight = positive(lossWeight, "lossWeight");
        this.baseWinWeight = positive(baseWinWeight, "baseWinWeight");
        this.xSplitWeight = positive(xSplitWeight, "xSplitWeight");
        this.xBombWildWeight = positive(xBombWildWeight, "xBombWildWeight");
        long total = (long) lossWeight + baseWinWeight + xSplitWeight + xBombWildWeight;
        if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("trial weights are too large");
        this.totalWeight = (int) total;
    }

    RoundMode select(SecureRandom random) {
        if (random == null) throw new IllegalArgumentException("random is required");
        int selected = random.nextInt(totalWeight);
        if (selected < lossWeight) return RoundMode.LOSS;
        selected -= lossWeight;
        if (selected < baseWinWeight) return RoundMode.BASE_WIN;
        selected -= baseWinWeight;
        if (selected < xSplitWeight) return RoundMode.XSPLIT;
        return RoundMode.XBOMB_WILD;
    }

    private static int positive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    public int getLossWeight() { return lossWeight; }
    public int getBaseWinWeight() { return baseWinWeight; }
    public int getXSplitWeight() { return xSplitWeight; }
    public int getXBombWildWeight() { return xBombWildWeight; }
    public int getTotalWeight() { return totalWeight; }
}
