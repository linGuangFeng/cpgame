package com.cpgame.junglekings;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBoundaryTest {
    private final IndependentVerifier verifier = new IndependentVerifier();
    private final MemberCodec codec = new MemberCodec();
    private final CompleteRoundFactory factory = new CompleteRoundFactory();

    @Test
    void rejectsS00014OnAnyBoard() {
        assertThrows(IllegalArgumentException.class, () ->
                GameRuleCore.expandBoard(List.of("S00014", "S00015", "S00015")));
    }

    @Test
    void rejectsUnconfirmedS00013TripleWin() {
        List<String> board = GameRuleCore.expandBoard(List.of("S00013", "S00013", "S00013"));
        assertThrows(IllegalArgumentException.class, () ->
                ResultUtil.evaluateSingleBoard(board, new BigDecimal("0.5")));
        assertThrows(IllegalArgumentException.class, () ->
                GameRuleCore.materialize(List.of(GameRuleCore.CB_TOP), List.of(board),
                        new BigDecimal("0.5"), 1));
    }

    @Test
    void s00011TriplePaysOneHundredTimesLineStake() {
        List<String> board = GameRuleCore.expandBoard(List.of("S00011", "S00011", "S00011"));
        ResultUtil.Evaluation evaluation = ResultUtil.evaluateSingleBoard(board, new BigDecimal("0.5"));
        assertEquals(RoundMode.WIN, evaluation.mode());
        assertEquals(100, evaluation.multiplier());
        assertEquals(0, new BigDecimal("50").compareTo(evaluation.winAmount()));
        assertEquals(List.of("PL0014"), evaluation.winPaylineKeys());
    }

    @Test
    void bothBoardsWinningApplyX2() {
        List<String> top = GameRuleCore.expandBoard(List.of("S00011", "S00011", "S00011"));
        List<String> bottom = GameRuleCore.expandBoard(List.of("S00012", "S00012", "S00012"));
        CompleteRound round = GameRuleCore.materialize(
                List.of(GameRuleCore.CB_TOP, GameRuleCore.CB_BOTTOM),
                List.of(top, bottom), new BigDecimal("0.5"), 1);
        verifier.verify(round);
        // line wins 50 + 25, both-win x2 = 150; bet = 1.00; multiplier = 150
        assertEquals(150, round.multiplier());
        assertEquals(0, new BigDecimal("150").compareTo(round.winAmount()));
        assertEquals(List.of(GameRuleCore.PL_TOP, GameRuleCore.PL_BOTTOM), round.winPaylineKeys());
    }

    @Test
    void codecRoundTripPreservesBoards() {
        SecureRandom random = new SecureRandom();
        CompleteRound round = factory.generate(RoundMode.LOSS, random, new BigDecimal("0.5"), 1);
        verifier.verifyCodecRoundTrip(round, codec);
        CompleteRound decoded = codec.decode(codec.encode(round));
        assertEquals(round.boards(), decoded.boards());
        assertEquals(round.mode(), decoded.mode());
    }

    @Test
    void factoryProducesRequestedClasses() {
        SecureRandom random = new SecureRandom();
        CompleteRound loss = factory.generate(RoundMode.LOSS, random, new BigDecimal("0.5"), 1);
        CompleteRound win = factory.generate(RoundMode.WIN, random, new BigDecimal("0.5"), 1);
        verifier.verify(loss);
        verifier.verify(win);
        assertEquals(RoundMode.LOSS, loss.mode());
        assertEquals(0, loss.multiplier());
        assertEquals(RoundMode.WIN, win.mode());
        assertTrue(win.multiplier() > 0);
        assertEquals(2, loss.chessboards().size());
        assertEquals(2, win.chessboards().size());
        assertEquals(2, loss.boards().size());
        assertEquals(2, win.boards().size());
    }

    @Test
    void redisKeysSplitSingleLineAndBothLines() {
        assertEquals("PerKeyList_000000002", RedisKeys.index(0));
        assertEquals("PerKeyList_100000002", RedisKeys.index(1));
        assertEquals("BetLog:000000002:000050", RedisKeys.list(0, 50));
        assertEquals("BetLog:100000002:000150", RedisKeys.list(1, 150));
        assertEquals(0, RedisKeys.betType(List.of(GameRuleCore.CB_TOP)));
        assertEquals(0, RedisKeys.betType(List.of(GameRuleCore.CB_BOTTOM)));
        assertEquals(1, RedisKeys.betType(GameRuleCore.CHESSBOARDS));
    }

    @Test
    void threeLineLayoutsEncodeDistinctMembers() {
        SecureRandom random = new SecureRandom();
        for (List<String> layout : GameRuleCore.LINE_LAYOUTS) {
            CompleteRound loss = factory.generate(RoundMode.LOSS, layout, random, new BigDecimal("0.5"), 1);
            CompleteRound win = factory.generate(RoundMode.WIN, layout, random, new BigDecimal("0.5"), 1);
            verifier.verifyCodecRoundTrip(loss, codec);
            verifier.verifyCodecRoundTrip(win, codec);
            assertEquals(layout, loss.chessboards());
            assertEquals(layout, win.chessboards());
            assertEquals(layout.size(), loss.boards().size());
            String ascii = new String(codec.encode(loss), java.nio.charset.StandardCharsets.US_ASCII);
            assertTrue(ascii.contains(String.join(",", layout)), ascii);
            if (layout.size() == 1) {
                assertEquals(0, new BigDecimal("0.5").compareTo(loss.betAmount()));
            } else {
                assertEquals(0, new BigDecimal("1").compareTo(loss.betAmount()));
            }
        }
    }

    @Test
    void dualBetIsDoubleTheLineStake() {
        List<String> miss = GameRuleCore.expandBoard(List.of("S00015", "S00012", "S00011"));
        CompleteRound round = GameRuleCore.materialize(
                List.of(GameRuleCore.CB_TOP, GameRuleCore.CB_BOTTOM),
                List.of(miss, miss), new BigDecimal("0.5"), 1);
        assertEquals(0, new BigDecimal("1").compareTo(round.betAmount()));
        assertEquals(RoundMode.LOSS, round.mode());
    }
}
