package com.hd.pg.appapi.business.vo.cpgame.luckypanda;

import java.math.BigDecimal;
import java.util.List;

/** Deterministic ResultUtil output for one delivery. No random state. */
public final class LuckyPandaEvaluation {
    private final List<LuckyPandaWin> wins;
    private final BigDecimal wa;
    private final int rpx;
    private final int rpxFactor;
    private final int scatterTokens;
    private final boolean segmentTerminal;
    private final List<String> wskl;
    private final List<List<List<Integer>>> wmkl;

    public LuckyPandaEvaluation(List<LuckyPandaWin> wins, BigDecimal wa, int rpx, int rpxFactor,
                                int scatterTokens, boolean segmentTerminal) {
        this.wins = List.copyOf(wins);
        this.wa = wa;
        this.rpx = rpx;
        this.rpxFactor = rpxFactor;
        this.scatterTokens = scatterTokens;
        this.segmentTerminal = segmentTerminal;
        this.wskl = wins.stream().map(w -> w.symbol().wireName()).toList();
        this.wmkl = wins.stream().map(LuckyPandaWin::wmkl).toList();
    }

    public List<LuckyPandaWin> wins() { return wins; }
    public BigDecimal wa() { return wa; }
    public int rpx() { return rpx; }
    public int rpxFactor() { return rpxFactor; }
    public int scatterTokens() { return scatterTokens; }
    public boolean segmentTerminal() { return segmentTerminal; }
    public int ss() { return segmentTerminal ? 1 : 0; }
    public List<String> wskl() { return wskl; }
    public List<List<List<Integer>>> wmkl() { return wmkl; }
    public boolean hasWaysWin() { return !wins.isEmpty(); }
}
