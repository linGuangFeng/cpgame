package com.cpgame.luckycatii;

import com.cpgame.luckycatii.model.ResultAnalysis;
import com.cpgame.luckycatii.model.RoundMode;
import com.cpgame.luckycatii.model.RoundResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class GameRuleCoreTest {
    private static final BigDecimal BS = new BigDecimal("0.1");
    private final GameRuleCore core = new GameRuleCore();
    private final RoundVerifier verifier = new RoundVerifier();

    @Test void batchLossNeverWinsAndStaysOrdinary() {
        Set<String> keys = new HashSet<>();
        Set<Object> boards = new HashSet<>();
        var rng = new SplittableRandom(50001);
        for (int i = 0; i < 200; i++) {
            RoundResult round = core.generateIndependentLoss(BS, 1, rng);
            ResultAnalysis inferred = verifier.verify(round);
            assertEquals(RoundMode.ORDINARY_LOSS, inferred.redisPoolMode());
            assertEquals(0, round.award().signum());
            assertEquals(0, round.gameMode());
            assertEquals(1, round.rpx());
            assertNull(ResultUtil.findLuckyTrigger(round.finalBoard()));
            assertTrue(keys.add(round.roundKey()));
            boards.add(round.finalBoard());
        }
        assertTrue(boards.size() > 80);
    }

    @Test void batchOrdinaryWinHasPaylinesWithoutSpecialModes() {
        var rng = new SplittableRandom(50002);
        for (int i = 0; i < 200; i++) {
            RoundResult round = core.generateOrdinaryWin(BS, 1, rng);
            ResultAnalysis inferred = verifier.verify(round);
            assertEquals(RoundMode.ORDINARY_WIN, inferred.redisPoolMode());
            assertTrue(round.award().signum() > 0);
            assertEquals(0, round.gameMode());
            assertEquals(1, round.rpx());
            assertFalse(inferred.luckyRespin());
            assertFalse(inferred.wheel());
        }
    }

    @Test void batchLuckyAndWheelAreCompleteEmbeddedRounds() {
        var rng = new SplittableRandom(50003);
        int lucky = 0, wheel = 0;
        for (int i = 0; i < 80; i++) {
            RoundResult luckyRound = core.generateLuckyRespin(BS, 1, rng);
            ResultAnalysis luckyAnalysis = verifier.verify(luckyRound);
            assertTrue(luckyAnalysis.luckyRespin());
            assertEquals(1, luckyRound.gameMode());
            assertEquals(2, luckyRound.steps().size());
            assertFalse(luckyRound.steps().get(1).paid());
            lucky++;
            RoundResult wheelRound = core.generateMultiplierWheel(BS, 1, rng);
            ResultAnalysis wheelAnalysis = verifier.verify(wheelRound);
            assertTrue(wheelAnalysis.wheel());
            assertTrue(GameRules.CONFIRMED_WHEEL_MULTIPLIERS.contains(wheelRound.rpx()));
            wheel++;
        }
        assertEquals(80, lucky);
        assertEquals(80, wheel);
    }

    @Test void asciiMemberRoundTripsAndRestoreOnlyCoreCannotGenerate() {
        GameRuleCore deterministic = GameRuleCore.forTesting(50004);
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec(new RoundFactory(), verifier);
        for (int i = 0; i < 90; i++) {
            RoundResult round = switch (i % 3) {
                case 0 -> deterministic.generateIndependentLoss(BS, 1);
                case 1 -> deterministic.generateOrdinaryWin(BS, 1);
                default -> deterministic.generateSpecial(BS, 1);
            };
            RoundResult rebuilt = codec.decodeRedisMember(codec.encodeRedisMemberString(round));
            verifier.verifyRecovery(round, rebuilt);
            assertFalse(codec.encodeRedisMemberString(round).contains("{"));
        }
        GameRuleCore restoreOnly = GameRuleCore.forRestoration();
        assertThrows(IllegalStateException.class, () -> restoreOnly.generateOrdinaryWin(BS, 1));
    }

    @Test void trainingKernelIsJointStatesNotPerCell() {
        RandomCandidateGenerator generator = new RandomCandidateGenerator();
        assertTrue(generator.lossCount() >= 1000);
        assertTrue(generator.winCount() >= 100);
        assertTrue(generator.luckyCount() >= 30);
        assertTrue(generator.wheelCount() >= 30);
        assertEquals(1465, generator.trainingKernelCount());
    }
}
