package com.cpgame.beachfun.core;
/** Separate production invariant and independent payout/transition checks. */
public final class RoundVerifier {
    private final GameRuleCore rules=new GameRuleCore();
    public void verify(GameRuleCore.CompleteRound round){rules.validate(round);ResultUtil.verify(round);}
}
