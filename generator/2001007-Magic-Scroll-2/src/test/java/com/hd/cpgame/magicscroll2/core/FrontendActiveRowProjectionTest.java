package com.hd.cpgame.magicscroll2.core;

import org.junit.Test;

import java.math.BigDecimal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression for B-BOARD-CODEC/B-BASE-ROUND/B-XSPLIT/B-XBOMB-WILD.
 * Expected row progression comes from the retained original GlobalEx/SlotResult code:
 * xSplit resolves in-place; a later win/cascade or xBomb opens exactly the fourth row.
 */
public final class FrontendActiveRowProjectionTest {
    private static final BigDecimal BET = new BigDecimal("0.40");

    @Test
    public void baseWinCascadeActivatesDrawableFourthRow() {
        assertFourthRowVisible(RoundMode.BASE_WIN, 61001L, 1);
    }

    @Test
    public void xSplitResolvesBeforeCascadeActivatesDrawableFourthRow() {
        GameRuleCore core = core();
        GeneratedRound round = core.generateCompleteRound(BET, RoundMode.XSPLIT, 61002L);
        DeliveryRuntimeUtil.Projection resolved = core.deliveryRuntimeUtil().project(round, 1);
        assertEquals("xSplit resolution must remain on the original three rows", 3,
                resolved.getActiveRows());
        assertEquals(-1, resolved.getActivatedRow());
        assertFourthRowVisible(core, round, 2);
    }

    @Test
    public void xBombCascadeActivatesDrawableFourthRow() {
        assertFourthRowVisible(RoundMode.XBOMB_WILD, 61003L, 1);
    }

    private static void assertFourthRowVisible(RoundMode mode, long seed, int deliveryIndex) {
        GameRuleCore core = core();
        GeneratedRound round = core.generateCompleteRound(BET, mode, seed);
        assertFourthRowVisible(core, round, deliveryIndex);
    }

    private static void assertFourthRowVisible(GameRuleCore core, GeneratedRound round,
                                               int deliveryIndex) {
        DeliveryRuntimeUtil.Projection projection = core.deliveryRuntimeUtil()
                .project(round, deliveryIndex);
        assertEquals("frontend-derived active row count", 4, projection.getActiveRows());
        assertEquals("zero-based fourth row index", 3, projection.getActivatedRow());
        assertTrue("the newly activated fourth row has no drawable symbol",
                projection.getVisibleSymbolsInActivatedRow() > 0);
        assertEquals(36, projection.getFormation().split(",", -1).length);
    }

    private static GameRuleCore core() {
        return new GameRuleCore(GenerationPolicy.defaults(),
                new TrialProbabilityPolicy(80, 12, 4, 4));
    }
}
