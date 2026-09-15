package com.hd.pg.appapi.business.vo.cpgame.luckypanda;

import java.math.BigDecimal;
import java.util.List;

/** One paying symbol's left-to-right ways hit on a single delivery. */
public final class LuckyPandaWin {
    private final LuckyPandaSymbol symbol;
    private final List<List<Integer>> wmkl;
    private final int ways;
    private final int reelCount;
    private final int pay;
    private final int rpxFactor;
    private final BigDecimal award;

    public LuckyPandaWin(LuckyPandaSymbol symbol, List<List<Integer>> wmkl, int ways, int reelCount,
                         int pay, int rpxFactor, BigDecimal award) {
        this.symbol = symbol;
        this.wmkl = wmkl.stream().map(List::copyOf).toList();
        this.ways = ways;
        this.reelCount = reelCount;
        this.pay = pay;
        this.rpxFactor = rpxFactor;
        this.award = award;
    }

    public LuckyPandaSymbol symbol() { return symbol; }
    public List<List<Integer>> wmkl() { return wmkl; }
    public int ways() { return ways; }
    public int reelCount() { return reelCount; }
    public int pay() { return pay; }
    public int rpxFactor() { return rpxFactor; }
    public BigDecimal award() { return award; }
}
