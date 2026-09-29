package com.cpgame.crazy777.generator;

import com.cpgame.crazy777.generator.model.RoundMode;
import com.cpgame.crazy777.generator.model.RoundResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class GameRuleCoreTest {
    private static final BigDecimal BS = new BigDecimal("0.5");
    private static final BigDecimal START = new BigDecimal("10000");
    private final GameRuleCore core = new GameRuleCore();
    private final RoundVerifier verifier = new RoundVerifier();

    @Test void batchLossHasUniqueKeysAndIndependentInference() {
        Set<String> keys = new HashSet<>();
        Set<Object> boards = new HashSet<>();
        var rng = new SplittableRandom(57001);
        for (int i = 0; i < 200; i++) {
            RoundResult round = core.generateIndependentLoss(1, BS, START, rng);
            var inferred = verifier.verify(round);
            assertEquals(RoundMode.ORDINARY_LOSS, inferred.mode());
            assertEquals(1, round.steps().size());
            assertTrue(keys.add(round.roundKey()));
            boards.add(round.boards().get(0));
            assertFalse(ResultUtil.isScatterTrigger(round.boards().get(0)));
        }
        assertTrue(boards.size() > 40, "LOSS 完整联合 kernel 应保持足够多样性: " + boards.size());
        assertEquals(1.0d, core.validateIndependentLossStrategy());
    }

    @Test void batchOrdinaryWinMatchesDerivedFields() {
        Set<String> keys = new HashSet<>();
        Set<Object> boards = new HashSet<>();
        var rng = new SplittableRandom(57002);
        for (int i = 0; i < 200; i++) {
            RoundResult round = core.generateOrdinaryWin(1, BS, START, rng);
            var inferred = verifier.verify(round);
            assertEquals(RoundMode.ORDINARY_WIN, inferred.mode());
            assertTrue(round.totalWin().signum() > 0);
            assertTrue(keys.add(round.roundKey()));
            boards.add(round.boards().get(0));
        }
        assertTrue(boards.size() > 30, "普通 WIN kernel 应保持多样性: " + boards.size());
    }

    @Test void batchFreeSpinsIsElevenStepsWithoutScatter() {
        Set<String> keys = new HashSet<>();
        var rng = new SplittableRandom(57003);
        for (int i = 0; i < 40; i++) {
            RoundResult round = core.generateFreeSpins(1, BS, START, rng);
            var inferred = verifier.verify(round);
            assertEquals(RoundMode.FREE_SPINS, inferred.mode());
            assertEquals(11, round.steps().size());
            assertTrue(ResultUtil.isScatterTrigger(round.boards().get(0)));
            for (int s = 1; s < 11; s++) {
                assertFalse(round.boards().get(s).contains("SC"));
                assertFalse(ResultUtil.hasPresentationOnlyWin(round.boards().get(s), true));
            }
            assertEquals(10, round.steps().get(0).fsn());
            assertEquals(0, round.steps().get(0).nfsc());
            assertEquals(10, round.steps().get(10).nfsc());
            assertEquals(1, round.steps().get(10).ss());
            assertTrue(keys.add(round.roundKey()));
        }
    }

    @Test void rejectsScreenshotStyleYellowVisualThreeOfAKindWhenProtocolLinesLose() {
        List<String> board = List.of(
                "H3", "BLANK", "H1", "BLANK", "H1",
                "H1", "BLANK", "H4", "BLANK", "H4",
                "H1", "BLANK", "H3", "BLANK", "H4");

        assertTrue(ResultUtil.evaluateRegularLines(board).isEmpty(), "协议固定坐标本身是 0 奖");
        assertEquals("H4", ResultUtil.evaluateDisplayedLines(board).get("2"),
                "历史页压缩 BLANK 后，底行会显示成黄渐变+黄空心+黄空心");
        assertTrue(ResultUtil.hasPresentationOnlyWin(board, true));
    }

    @Test void asciiMemberRoundTripsWithoutJson() {
        GameRuleCore deterministic = GameRuleCore.forTesting(57004);
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec(new RoundFactory(), verifier);
        for (int i = 0; i < 30; i++) {
            RoundResult round = switch (i % 3) {
                case 0 -> deterministic.generateIndependentLoss(1, BS, START);
                case 1 -> deterministic.generateOrdinaryWin(1, BS, START);
                default -> deterministic.generateFreeSpins(1, BS, START);
            };
            String payload = codec.encodeRedisMemberString(round);
            assertFalse(payload.startsWith("{") || payload.startsWith("["));
            assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(payload));
            RoundResult rebuilt = codec.decodeRedisMember(payload, round.bs(), round.bl(), round.startingBalance());
            assertEquals(verifier.verify(round).mode(), verifier.verify(rebuilt).mode());
            assertEquals(0, round.totalWin().divide(round.betAmount())
                    .compareTo(rebuilt.totalWin().divide(rebuilt.betAmount())));
            if (!payload.equals("#")) verifier.verifyRecovery(round, rebuilt);
            assertFalse(payload.contains(","));
        }
    }

    @Test void restoreOnlyCoreRejectsGeneration() {
        assertThrows(IllegalStateException.class,
                () -> GameRuleCore.forRestoration().generateIndependentLoss(1, BS, START));
    }
}
