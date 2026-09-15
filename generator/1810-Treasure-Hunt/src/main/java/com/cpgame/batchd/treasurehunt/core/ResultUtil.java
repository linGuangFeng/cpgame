package com.cpgame.batchd.treasurehunt.core;

public final class ResultUtil {
    private ResultUtil(){}
    public static GameRuleCore.Evaluation calculate(String fact){return GameRuleCore.evaluate(MinimalRoundFactCodec.decode(fact));}
}
