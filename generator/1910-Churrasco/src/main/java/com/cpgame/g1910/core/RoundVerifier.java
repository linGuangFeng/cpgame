package com.cpgame.g1910.core;

/** Fail-closed corpus guard. */
public final class RoundVerifier {
    private RoundVerifier() { }
    public static void verify(GameRuleCore.CompleteRound round, int maxUnits) {
        String fact=MinimalRoundFactCodec.encode(round);
        var first=GameRuleCore.evaluate(round); var second=ResultUtil.calculate(fact);
        if(first.totalUnits()!=second.totalUnits()||first.outcome()!=second.outcome())throw new IllegalStateException("codec/result mismatch");
        if(first.totalUnits()<0||first.totalUnits()>maxUnits)throw new IllegalArgumentException("round multiplier outside configured cap");
    }
}
