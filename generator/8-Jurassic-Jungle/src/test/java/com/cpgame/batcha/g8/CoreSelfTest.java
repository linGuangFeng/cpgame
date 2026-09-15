package com.cpgame.batcha.g8;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.security.SecureRandom;
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
        assertEquals(RoundMode.LOSS, round.mode());
        assertEquals(1, round.steps().size());
        assertEquals(0, round.unitRatio());
    }
}
