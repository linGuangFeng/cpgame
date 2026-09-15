package com.cpgame.batchc.cybergo;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.Test;

class GameRuleCoreTest {
    private static GameRuleCore reproducible(long seed) {
        return new GameRuleCore(RandomGeneratorFactory.of("L64X128MixRandom").create(seed));
    }

    @Test
    void independentOracleReproducesCapturedWaysExample() {
        List<String> board = List.of("S2","S2","S1", "S2","S1","S1", "S1","WILD","S1",
                "WILD","S1","S1", "S1","S1","S3");
        ResultUtil.Evaluation result = ResultUtil.evaluate(board, 1, new BigDecimal("0.02"));
        assertEquals(new BigDecimal("109.80"), result.baseWin());
        assertEquals(List.of("S1", "S2"), result.winningSymbols());
    }

    @Test
    void generatedLossesAreFreshSingleStepAndIndependentlyVerified() {
        GameRuleCore core = reproducible(52L);
        CompleteRoundVerifier verifier = new CompleteRoundVerifier(GenerationLimits.defaults());
        String previous = null;
        for (int index = 0; index < 100; index++) {
            CyberGoModels.CompleteRound round = core.generateOrdinaryLoss();
            CompleteRoundVerifier.Verification actual = verifier.verify(round);
            assertEquals(CyberGoModels.RoundKind.ORDINARY_LOSS, actual.inferredKind());
            assertEquals(1, actual.deliveryCount());
            assertNotEquals(previous, round.roundKey());
            previous = round.roundKey();
        }
    }

    @Test
    void completeFreeRoundHasStrictOrderMultiplierAndTerminalState() {
        CyberGoModels.CompleteRound round = reproducible(5204L).generateFreeSpinRound();
        ResultUtil.RoundResult reversed = ResultUtil.reverse(round);
        int fsn = reversed.freeSpinCount();
        assertTrue(fsn == 12 || fsn == 15 || fsn == 20);
        assertEquals(fsn + 1, round.deliveries().size());
        int multiplier = 2;
        int wilds = 0;
        for (int index = 1; index <= fsn; index++) {
            wilds += reversed.stepEvaluations().get(index).wildCount();
            while (wilds >= 3 && multiplier < 20) { multiplier += 2; wilds -= 3; }
            assertEquals(index, round.deliveries().get(index).nfsc());
            assertEquals(multiplier, round.deliveries().get(index).rpx());
        }
        assertTrue(round.deliveries().getLast().terminal());
        assertEquals(0, round.deliveries().getLast().ss());
    }

    @Test
    void randomGenerationIsInferredWithoutPresetModeWeights() {
        GameRuleCore core = reproducible(5252L);
        CompleteRoundVerifier verifier = new CompleteRoundVerifier(GenerationLimits.defaults());
        for (int index = 0; index < 1_000; index++) verifier.verify(core.generateCompleteRound());
    }

    @Test
    void amountTamperingIsRejectedByReverseCalculation() {
        CyberGoModels.CompleteRound original = reproducible(7L).generateOrdinaryWin();
        CyberGoModels.Step step = original.deliveries().getFirst();
        CyberGoModels.Step changed = new CyberGoModels.Step(step.ba(), step.bid(), step.bl(), step.bs(), step.ca(),
                step.fsn(), step.frwa(), step.gt(), step.nfsc(), step.rpx(), step.rskl(), step.rwa(),
                step.small_game_type(), step.ss(), step.wa().add(new BigDecimal("0.01")), step.wmkl(), step.wskl());
        CyberGoModels.CompleteRound tampered = new CyberGoModels.CompleteRound(original.roundKey(), original.kind(),
                original.bet(), original.generatedAtEpochSecond(), List.of(changed));
        assertThrows(IllegalArgumentException.class, () -> ResultUtil.reverse(tampered));
    }

    @Test
    void minimalFactsRebuildSameRuleOutcome() {
        GameRuleCore core = reproducible(88L);
        MinimalFactCodec codec = new MinimalFactCodec();
        for (int index = 0; index < 100; index++) {
            CyberGoModels.CompleteRound original = core.generateCompleteRound();
            ResultUtil.RoundResult before = ResultUtil.reverse(original);
            CyberGoModels.CompleteRound rebuilt = core.rebuild(codec.extract(original));
            ResultUtil.RoundResult after = ResultUtil.reverse(rebuilt);
            assertEquals(before.inferredKind(), after.inferredKind());
            assertEquals(before.totalWin(), after.totalWin());
            assertEquals(original.deliveries().size(), rebuilt.deliveries().size());
        }
    }

    @Test
    void redisMemberContainsOnlyBoardsAndRebuildsSameOutcome() {
        MinimalFactCodec codec = new MinimalFactCodec();
        GameRuleCore core = reproducible(9L);
        CyberGoModels.CompleteRound original = core.generateFreeSpinRound();
        ResultUtil.RoundResult before = ResultUtil.reverse(original);
        byte[] member = codec.encodeForRedis(codec.extract(original));
        assertTrue(codec.redisEncodingEnabled());
        assertEquals(original.deliveries().size() * 15 + original.deliveries().size() - 1, member.length);
        assertTrue(new String(member, java.nio.charset.StandardCharsets.US_ASCII)
                .matches("[0-9]{15}(\\|[0-9]{15})*"));
        CyberGoModels.CompleteRound rebuilt = core.rebuild(codec.decodeFromRedis(member));
        ResultUtil.RoundResult after = ResultUtil.reverse(rebuilt);
        assertEquals(before.inferredKind(), after.inferredKind());
        assertEquals(before.totalWin(), after.totalWin());
        assertEquals(original.deliveries().size(), rebuilt.deliveries().size());
    }

    @Test
    void formalVerificationPublishesRandomFactsFingerprintWithoutSeed() {
        RoundEngineVerifier.Summary first = RoundEngineVerifier.verify(
                GenerationLimits.defaults(), 10, 1_000, new BigDecimal("0.50"));
        RoundEngineVerifier.Summary second = RoundEngineVerifier.verify(
                GenerationLimits.defaults(), 10, 1_000, new BigDecimal("0.50"));
        assertEquals(64, first.generatedFactsSha256().length());
        assertEquals(64, second.generatedFactsSha256().length());
        assertNotEquals(first.generatedFactsSha256(), second.generatedFactsSha256());
    }
}
