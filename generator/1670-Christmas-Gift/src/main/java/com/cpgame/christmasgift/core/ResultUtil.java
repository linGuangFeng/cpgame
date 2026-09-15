package com.cpgame.christmasgift.core;

/** Independent result oracle used by both generation validation and runtime presentation. */
public final class ResultUtil {
    private static final GameRuleCore RULES = new GameRuleCore();
    private ResultUtil() {}
    public static GameRuleCore.Evaluation evaluate(GameRuleCore.CompleteRound round) { return RULES.evaluate(round); }
    public static int multiplier(GameRuleCore.CompleteRound round) { return evaluate(round).multiplier(); }
}
