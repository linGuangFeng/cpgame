package com.cpgame.junglekings;

import java.math.BigDecimal;
import java.util.List;

/** Recomputes a complete round from board facts before delivery. */
public final class IndependentVerifier {
    public IndependentVerifier() { }

    public void verify(CompleteRound round) {
        if (round.rawGameId() != GameRuleCore.RAW_GAME_ID) fail("raw game id is not 2");
        GameRuleCore.validateBet(round.betSize(), round.betLevel());
        if (round.chessboards().isEmpty() || round.chessboards().size() > 2) fail("ckl must be 1 or 2 boards");
        if (round.boards().size() != round.chessboards().size()) fail("board count does not match ckl");
        for (int i = 0; i < round.boards().size(); i++) {
            List<String> reels = GameRuleCore.logicalReels(round.boards().get(i));
            GameRuleCore.requireConfirmedWinBoundary(reels);
        }
        ResultUtil.Evaluation independent = ResultUtil.evaluate(
                round.chessboards(), round.boards(), round.betSize(), round.betLevel());
        if (independent.mode() != round.mode()) fail("mode mismatch");
        equal(independent.betAmount(), round.betAmount(), "bet_amount");
        equal(independent.winAmount(), round.winAmount(), "win_amount");
        if (independent.multiplier() != round.multiplier()) fail("multiplier mismatch");
        if (!independent.winPaylineKeys().equals(round.winPaylineKeys())) fail("payline mismatch");
        if (!independent.winSymbolKeys().equals(round.winSymbolKeys())) fail("win symbol mismatch");
        if (round.multiplier() < 0) fail("negative multiplier");
        if (round.chessboards().size() == 2 && independent.winPaylineKeys().size() == 2
                && independent.winAmount().signum() > 0) {
            BigDecimal lineStake = GameRuleCore.lineStake(round.betSize(), round.betLevel());
            BigDecimal raw = BigDecimal.ZERO;
            for (int i = 0; i < 2; i++) {
                raw = raw.add(ResultUtil.evaluateLine(round.chessboards().get(i),
                        round.boards().get(i), lineStake).winAmount());
            }
            equal(GameRuleCore.money(raw.multiply(BigDecimal.valueOf(2))), round.winAmount(), "x2 both-win");
        }
    }

    public void verifyCodecRoundTrip(CompleteRound round, MemberCodec codec) {
        verify(round);
        CompleteRound decoded = codec.decode(codec.encode(round));
        verify(decoded);
        equal(round.winAmount(), decoded.winAmount(), "codec win_amount");
        if (round.multiplier() != decoded.multiplier()) fail("codec multiplier");
        if (!round.chessboards().equals(decoded.chessboards())) fail("codec chessboards");
        if (!round.boards().equals(decoded.boards())) fail("codec boards");
    }

    private static void equal(BigDecimal left, BigDecimal right, String name) {
        if (left.compareTo(right) != 0) fail(name + " " + left + " != " + right);
    }

    private static void fail(String message) {
        throw new IllegalArgumentException(message);
    }
}
