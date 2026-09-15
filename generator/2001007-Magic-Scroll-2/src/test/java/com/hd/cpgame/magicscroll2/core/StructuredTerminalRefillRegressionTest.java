package com.hd.cpgame.magicscroll2.core;

import org.junit.Test;

import java.math.BigDecimal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 固化验收拒绝中的 XSPLIT/0.40/4701L 确定性终局生成缺陷。 */
public final class StructuredTerminalRefillRegressionTest {
    private static final BigDecimal PAID_BET = new BigDecimal("0.40");
    private static final BigDecimal BASE_BET = new BigDecimal("0.02");

    @Test
    public void seed4701AlwaysBuildsAndIndependentlyVerifiesCompleteXSplitRound() {
        GenerationPolicy deliberatelyTinyRetryBudget = new GenerationPolicy(
                1, 1, 1000, 90, 30, 10, 20000, 300);
        GameRuleCore core = new GameRuleCore(deliberatelyTinyRetryBudget,
                new TrialProbabilityPolicy(80, 12, 4, 4));
        byte[] stableFacts = null;
        for (int repetition = 0; repetition < 25; repetition++) {
            GeneratedRound round = core.generateCompleteRound(PAID_BET, RoundMode.XSPLIT, 4701L);
            RoundVerifier.Verification verification = core.verifier().verify(round);
            assertEquals(RoundMode.XSPLIT, verification.getInferredMode());
            assertEquals(3, round.getSteps().size());
            assertTrue(round.getSteps().get(2).isTerminal());
            assertTrue(round.getPayout().signum() > 0);
            for (int index = 1; index < round.getSteps().size(); index++) {
                new AdjacentStepVerifier().verify(round.getSteps().get(index - 1),
                        round.getSteps().get(index), BASE_BET);
            }
            ResultUtil.Inspection terminal = core.resultUtil().inspect(
                    round.getSteps().get(2).getFormation(), 4, BASE_BET, 1);
            assertTrue(core.resultUtil().isTerminalBaseStep(terminal));
            byte[] facts = core.minimalFactCodec().encode(round);
            if (stableFacts == null) stableFacts = facts;
            else assertArrayEquals(stableFacts, facts);
        }
    }
}
