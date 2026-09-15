package com.cpgame.batchd.treasurehunt.core;

public final class RoundVerifier {
    private RoundVerifier(){}
    public static void verify(GameRuleCore.CompleteRound round,int normalMax,int specialMax){var expected=GameRuleCore.evaluate(round);String fact=MinimalRoundFactCodec.encode(round);var decoded=MinimalRoundFactCodec.decode(fact);var actual=ResultUtil.calculate(fact);if(!round.mode().equals(decoded.mode())||round.steps().size()!=decoded.steps().size()||expected.totalUnits()!=actual.totalUnits()||expected.outcome()!=actual.outcome())throw new IllegalStateException("round codec verification failed");int max=round.mode()==GameRuleCore.Mode.ORDINARY?normalMax:specialMax;if(actual.totalUnits()>max)throw new IllegalStateException("multiplier exceeds configured cap: "+actual.totalUnits());}
}
