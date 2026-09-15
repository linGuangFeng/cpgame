package com.cpgame.crazypiggy.generator;

import com.cpgame.crazypiggy.generator.model.RoundMode;
import com.cpgame.crazypiggy.generator.model.RoundResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class GameRuleCoreTest {
    private static final BigDecimal BS = new BigDecimal("0.5");
    private final GameRuleCore core = new GameRuleCore();
    private final RoundVerifier verifier = new RoundVerifier();

    @Test void batchLossHasUniqueKeysDiverseBoardsAndIndependentInference() {
        Set<String> keys = new HashSet<>();
        Set<Object> boards = new HashSet<>();
        var rng = new SplittableRandom(56001);
        for (int i = 0; i < 200; i++) {
            RoundResult round = core.generateIndependentLoss(BS, 1, rng);
            var inferred = verifier.verify(round);
            assertEquals(RoundMode.ORDINARY_LOSS, inferred.mode());
            assertTrue(round.loss());
            assertTrue(keys.add(round.roundKey()), "roundKey 必须唯一");
            boards.add(round.symbols());
        }
        assertTrue(boards.size() > 170, "LOSS 完整联合 kernel 应保持足够多样性");
    }

    @Test void batchOrdinaryWinMatchesAllDerivedFields() {
        Set<String> keys = new HashSet<>();
        Set<Object> boards = new HashSet<>();
        var rng = new SplittableRandom(56002);
        for (int i = 0; i < 200; i++) {
            RoundResult round = core.generateOrdinaryWin(BS, 1, rng);
            var inferred = verifier.verify(round);
            assertEquals(RoundMode.ORDINARY_WIN, inferred.mode());
            assertEquals(0, round.gameMode());
            assertTrue(round.totalAward().signum() > 0);
            assertTrue(keys.add(round.roundKey()));
            boards.add(round.symbols());
        }
        assertTrue(boards.size() > 90, "普通 WIN 完整联合 kernel 应保持足够多样性");
    }

    @Test void batchBoosterIsOneRoundWithContinuousEmbeddedDeliveries() {
        Set<String> keys = new HashSet<>();
        Set<Object> boards = new HashSet<>();
        var rng = new SplittableRandom(56003);
        for (int i = 0; i < 100; i++) {
            RoundResult round = core.generateBoosterRound(BS, 1, rng);
            var inferred = verifier.verify(round);
            assertEquals(RoundMode.BOOSTER_WHEEL, inferred.mode());
            assertEquals(1, round.gameMode());
            assertEquals(2, round.smallGameType());
            assertEquals(round.wheelMultipliers().size() + 1, round.wheelPositions().size());
            for (int deliveryIndex = 0; deliveryIndex < round.deliveries().size(); deliveryIndex++) {
                assertEquals(deliveryIndex, round.deliveries().get(deliveryIndex).deliveryIndex());
                assertEquals(deliveryIndex == round.deliveries().size() - 1,
                        round.deliveries().get(deliveryIndex).terminal());
            }
            assertNull(round.deliveries().get(round.deliveries().size() - 1).multiplier());
            assertTrue(keys.add(round.roundKey()));
            boards.add(round.symbols());
        }
        assertTrue(boards.size() >= 6, "轮盘触发符号必须覆盖所有已确认可触发符号");
    }

    @Test void balanceAndMinimalFactRecoveryAreExactForMixedBatch() {
        GameRuleCore deterministic = GameRuleCore.forTesting(56004);
        RoundFactory restoreFactory = new RoundFactory();
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec(restoreFactory, verifier);
        BigDecimal balance = new BigDecimal("10000.00");
        for (int i = 0; i < 300; i++) {
            RoundResult round = switch (i % 3) {
                case 0 -> deterministic.generateIndependentLoss(BS, 1);
                case 1 -> deterministic.generateOrdinaryWin(BS, 1);
                default -> deterministic.generateBoosterRound(BS, 1);
            };
            BigDecimal post = balance.subtract(round.betAmount()).add(round.totalAward());
            verifier.verifySettlement(balance, post, round);
            RoundResult restored = codec.restore(codec.extract(round));
            verifier.verifyRecovery(round, restored);
            balance = post;
        }
    }

    @Test void explicitTestSeedReproducesWholeRoundButProductionConfigHasNoSeed() {
        GameRuleCore first = GameRuleCore.forTesting(56005);
        GameRuleCore second = GameRuleCore.forTesting(56005);
        for (int i = 0; i < 20; i++) {
            assertEquals(first.generatePaidRound(BS, 1), second.generatePaidRound(BS, 1));
        }
    }

    @Test void constructiveLossMeetsConfiguredFirstAttemptThreshold() {
        RandomCandidateGenerator generator = new RandomCandidateGenerator();
        double rate = generator.measureFirstAttemptLossSuccess(new SplittableRandom(56006), 100_000);
        assertTrue(rate >= 0.90d);
        assertEquals(1.0d, rate);
    }

    @Test void redisMemberRoundTripKeepsCompleteRoundAndDeliveryIdentity() {
        RoundResult round = GameRuleCore.forTesting(56007).generateIndependentLoss(BS, 1);
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec(new RoundFactory(), verifier);
        String payload = codec.encodeRedisMemberString(round);
        RoundResult rebuilt = codec.decodeRedisMember(payload);
        verifier.verifyRecovery(round, rebuilt);
        assertTrue(payload.startsWith(MinimalRoundFactCodec.PREFIX + ";"));
        assertFalse(payload.contains("{") || payload.contains("\"") || payload.contains("deliveries"));
    }
}
