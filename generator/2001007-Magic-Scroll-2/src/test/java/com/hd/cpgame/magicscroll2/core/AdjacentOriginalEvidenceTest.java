package com.hd.cpgame.magicscroll2.core;

import org.junit.Test;

import java.math.BigDecimal;

import static org.junit.Assert.assertEquals;

/** 独立预期取自原站旧 Round 29 与 2026-08-29 补抓 Round 18，不由生成器产生。 */
public final class AdjacentOriginalEvidenceTest {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal BASE_BET = new BigDecimal("0.02");

    @Test
    public void verifiesOriginalXSplitRowTransformationCellByCell() {
        RoundStep before = step("2,3,3,199,199,199,12,7,199,99,99,199,9,5,10,99,199,199,6,12,8,199,199,199,12,9,8,99,99,199,7,9,8,99,99,199", 3, 1);
        RoundStep after = step("10,3,3,199,199,199,1012,7,199,99,99,199,1009,5,10,99,199,199,1006,12,8,199,199,199,1012,9,8,99,99,199,1007,9,8,99,99,199", 3, 1);
        AdjacentStepVerifier.TransitionInspection result = new AdjacentStepVerifier().verify(before, after, BASE_BET);
        assertEquals("XSPLIT_ROW_TRANSFORM", result.getTransitionType());
        assertEquals(1, result.getRemovedCellCount());
    }

    @Test
    public void verifiesOriginalCollapseRetentionMovementAndRefillCellByCell() {
        RoundStep before = step("12,6,9,199,199,199,12,8,5,5,12,199,7,5,9,12,199,199,3,4,11,7,6,199,12,8,8,12,12,199,12,3,3,9,5,199", 5, 2);
        RoundStep after = step("11,6,9,199,199,199,9,10,8,5,5,199,11,7,5,9,199,199,3,4,11,7,6,199,12,8,8,12,12,199,12,3,3,9,5,199", 6, 2);
        AdjacentStepVerifier.TransitionInspection result = new AdjacentStepVerifier().verify(before, after, BASE_BET);
        assertEquals("ELIMINATE_FALL_REFILL", result.getTransitionType());
        assertEquals(36, result.getRetainedMoveCount() + result.getRefillCellCount());
    }

    @Test
    public void verifiesFreshCapturedOrdinaryWinCollapseCellByCell() {
        RoundStep before = step("9,7,11,199,199,100,11,11,199,99,99,99,7,11,101,99,99,99,10,9,12,199,199,199,9,3,4,99,99,199,10,4,7,100,199,199", 3, 1);
        RoundStep after = step("8,9,7,199,199,100,7,11,10,199,99,99,8,10,7,101,99,99,10,9,12,199,199,199,12,9,3,4,99,199,10,4,7,100,199,199", 4, 1);
        AdjacentStepVerifier.TransitionInspection result = new AdjacentStepVerifier().verify(before, after, new BigDecimal("0.40"));
        assertEquals("ELIMINATE_FALL_REFILL", result.getTransitionType());
        assertEquals(24, result.getRetainedMoveCount() + result.getRefillCellCount());
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsSuddenlyReplacedUnrelatedBoard() {
        RoundStep before = step("12,6,9,199,199,199,12,8,5,5,12,199,7,5,9,12,199,199,3,4,11,7,6,199,12,8,8,12,12,199,12,3,3,9,5,199", 5, 2);
        RoundStep unrelated = step("3,4,5,6,7,8,4,5,6,7,8,9,5,6,7,8,9,10,6,7,8,9,10,11,7,8,9,10,11,12,8,9,10,11,12,3", 6, 2);
        new AdjacentStepVerifier().verify(before, unrelated, BASE_BET);
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsSingleIllegalRefillEncodingMutation() {
        RoundStep before = step("9,7,11,199,199,100,11,11,199,99,99,99,7,11,101,99,99,99,10,9,12,199,199,199,9,3,4,99,99,199,10,4,7,100,199,199", 3, 1);
        // 原站 Round 18 的合法补位 (column=0,row=0) 为 8；仅将该格篡改为未证实符号 13。
        RoundStep tampered = step("13,9,7,199,199,100,7,11,10,199,99,99,8,10,7,101,99,99,10,9,12,199,199,199,12,9,3,4,99,199,10,4,7,100,199,199", 4, 1);
        new AdjacentStepVerifier().verify(before, tampered, new BigDecimal("0.40"));
    }

    private static RoundStep step(String formation, int rows, int multiplier) {
        return new RoundStep(formation, rows, multiplier, ZERO, ZERO, false);
    }
}
