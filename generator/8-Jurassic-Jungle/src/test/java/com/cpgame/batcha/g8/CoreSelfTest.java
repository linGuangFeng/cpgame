package com.cpgame.batcha.g8;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class CoreSelfTest {
    @Test
    void originOraclePaysAndClusters() throws Exception {
        CaptureOracleVerifier.Result result = CaptureOracleVerifier.verify();
        assertEquals(0, result.payMismatch(), result.issues().toString());
        assertEquals(0, result.clusterMismatch(), result.issues().toString());
        assertEquals(0, result.materializeFail(), result.issues().toString());
        assertTrue(result.rounds() >= 100);
    }

    @Test
    void lossRoundTerminatesWithoutDragon() {
        List<String> board = List.of(
            "S2", "S3", "S4", "S5", "S6",
            "S7", "S8", "S9", "S2", "S3",
            "S4", "S5", "S6", "S7", "S8",
            "S9", "S2", "S3", "S4", "S5",
            "S6", "S7", "S8", "S9", "S2");
        GameRuleCore.BoardResult result = GameRuleCore.evaluateBoard(board, new BigDecimal("0.05"), 4);
        assertEquals(0, result.winAmount().signum());
    }

    @Test
    void codecRoundTripLoss() {
        CompleteRoundFactory factory = new CompleteRoundFactory(GameRuleCore.MAX_STEPS_OBSERVED);
        CompleteRound round = factory.generate(RoundMode.LOSS, new SecureRandom(), new BigDecimal("0.05"), 4);
        MemberCodec codec = new MemberCodec();
        IndependentVerifier verifier = new IndependentVerifier(new BigDecimal("20000"), GameRuleCore.MAX_STEPS_OBSERVED);
        verifier.verifyCodecRoundTrip(round, codec);
        String full = new String(codec.encodeFull(round), StandardCharsets.US_ASCII);
        assertTrue(full.startsWith("JJ8A4|"));
        assertTrue(!full.contains(","));
        assertEquals(RoundMode.LOSS, round.mode());
        assertEquals(1, round.steps().size());
        assertEquals(0, round.unitRatio());
    }

    @Test
    void giantGoesToMaryOtherRewardsAreOrdinary() {
        List<String> board = List.of(
            "S2", "S3", "S4", "S5", "S6",
            "S7", "S8", "S9", "S2", "S3",
            "S4", "S5", "S6", "S7", "S8",
            "S9", "S2", "S3", "S4", "S5",
            "S6", "S7", "S8", "S9", "S2");
        BigDecimal paid = new BigDecimal("2");
        BigDecimal betSize = new BigDecimal("0.05");
        BigDecimal payout = new BigDecimal("1");
        Step loss = Step.fact(0, paid, betSize, 4, board, List.of(), 1, 0, 0);
        Step ordinaryWin = Step.fact(0, paid, betSize, 4, board, List.of(), 1, 0, 0);
        Step earth = Step.fact(0, paid, betSize, 4, board, List.of(), 1, 0, 1);
        Step water = Step.fact(0, paid, betSize, 4, board, List.of(), 1, 0, 2);
        Step fire = Step.fact(0, paid, betSize, 4, board, List.of(), 1, 0, 3);
        Step giant = Step.fact(0, paid, betSize, 4, board, List.of(), 1, 0, 4);

        assertEquals(RoundMode.LOSS, GameRuleCore.classify(List.of(loss), BigDecimal.ZERO));
        assertEquals(RoundMode.WIN, GameRuleCore.classify(List.of(ordinaryWin), payout));
        assertEquals(RoundMode.WIN, GameRuleCore.classify(List.of(earth), payout));
        assertEquals(RoundMode.WIN, GameRuleCore.classify(List.of(water), payout));
        assertEquals(RoundMode.WIN, GameRuleCore.classify(List.of(fire), payout));
        assertEquals(RoundMode.DRAGON, GameRuleCore.classify(List.of(giant), payout));
        assertTrue(GameRuleCore.reachedGiant(List.of(giant)));
        assertTrue(!GameRuleCore.reachedGiant(List.of(earth, water, fire)));

        assertTrue(!RedisKeys.special(RoundMode.LOSS));
        assertTrue(!RedisKeys.special(RoundMode.WIN));
        assertTrue(RedisKeys.special(RoundMode.DRAGON));
        assertTrue(RedisKeys.list(RoundMode.WIN, 10).startsWith("BetLog:"));
        assertTrue(RedisKeys.list(RoundMode.DRAGON, 10).startsWith("MaryLog:"));
    }

    @Test
    void generatedDragonRoundIsGiantAndMary() {
        CompleteRoundFactory factory = new CompleteRoundFactory(GameRuleCore.MAX_STEPS_OBSERVED);
        CompleteRound dragon = factory.generate(RoundMode.DRAGON, new SecureRandom(), new BigDecimal("0.05"), 4);
        assertEquals(RoundMode.DRAGON, dragon.mode());
        assertTrue(GameRuleCore.reachedGiant(dragon.steps()));
        assertTrue(RedisKeys.special(dragon.mode()));

        CompleteRound win = factory.generate(RoundMode.WIN, new SecureRandom(), new BigDecimal("0.05"), 4);
        assertEquals(RoundMode.WIN, win.mode());
        assertTrue(!GameRuleCore.reachedGiant(win.steps()));
        assertTrue(!RedisKeys.special(win.mode()));
    }
}
