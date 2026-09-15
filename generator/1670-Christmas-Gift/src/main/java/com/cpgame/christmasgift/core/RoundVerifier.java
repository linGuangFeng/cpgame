package com.cpgame.christmasgift.core;

/** Fails a candidate unless decode and the independent oracle agree exactly. */
public final class RoundVerifier {
    private final MinimalRoundFactCodec codec = new MinimalRoundFactCodec();
    public GameRuleCore.Evaluation verify(GameRuleCore.CompleteRound round) {
        GameRuleCore.Evaluation before = ResultUtil.evaluate(round);
        GameRuleCore.CompleteRound decoded = codec.decode(codec.encode(round));
        GameRuleCore.Evaluation after = ResultUtil.evaluate(decoded);
        if (!before.equals(after)) throw new IllegalStateException("round codec/oracle mismatch");
        return before;
    }
}
