package com.cpgame.g1910.core;

/** Independent public calculation entry used by Loader and Controller integrity checks. */
public final class ResultUtil {
    private ResultUtil() { }
    public static GameRuleCore.Evaluation calculate(String fact) { return GameRuleCore.evaluate(MinimalRoundFactCodec.decode(fact)); }
    public static int totalUnits(String fact) { return calculate(fact).totalUnits(); }
}
