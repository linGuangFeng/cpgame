package com.hd.pg.appapi.business.vo.cpgame.luckypanda;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelpAndFrameTest {
    private static final BigDecimal BS = new BigDecimal("0.02");

    @Test
    void fourTreasuresAwardTenAndEachExtraBlockAddsTwo() {
        assertEquals(0, LuckyPandaResultUtil.scatterFreeAwarded(3));
        assertEquals(10, LuckyPandaResultUtil.scatterFreeAwarded(4));
        assertEquals(12, LuckyPandaResultUtil.scatterFreeAwarded(5));
        assertEquals(12, LuckyPandaResultUtil.scatterFreeAwarded(GameRuleCore.SCAT_TOTAL_MAX_BLOCKS));
    }

    @Test
    void fiveScatterBlocksOnALossPageTriggerTwelveFreeSpins() {
        LuckyPandaBoard board = LuckyPandaBoard.fromRskl(List.of(
                "1Scat", "1Scat", "1H1", "1H2", "1H3",
                "1Scat", "1A", "1K", "1Q", "1J", "1T",
                "1Scat", "1H4", "1H5", "1A", "1K", "1Q",
                "1H1", "1H2", "1H3", "1H4", "1H5", "1A",
                "1K", "1Q", "1J", "1T", "1H1", "1H2",
                "1Scat", "1H3", "1H4", "1H5", "1A"));
        assertEquals(5, board.scatterTokens());
        LuckyPandaEvaluation evaluation = LuckyPandaResultUtil.evaluate(board, BS, 1, 0);
        assertFalse(evaluation.hasWaysWin());
        assertTrue(LuckyPandaResultUtil.scatterFreeTrigger(evaluation, 0));
        assertEquals(12, LuckyPandaResultUtil.scatterFreeAwarded(evaluation.scatterTokens()));
    }

    @Test
    void generatedWildNeverLandsOnOuterReelsOrInnerTop() {
        LuckyPandaBoardGenerator boards = new LuckyPandaBoardGenerator(new Random(41), weights());
        for (int i = 0; i < 200; i++) {
            LuckyPandaBoard board = boards.generate(WeightScene.PAID_START);
            for (LuckyPandaToken token : board.reel(0)) {
                assertFalse(token.symbol() == LuckyPandaSymbol.WILD);
            }
            for (LuckyPandaToken token : board.reel(5)) {
                assertFalse(token.symbol() == LuckyPandaSymbol.WILD);
            }
            for (int reel = 1; reel <= 4; reel++) {
                LuckyPandaToken top = board.reel(reel).get(0);
                assertTrue(top.top());
                assertFalse(top.symbol() == LuckyPandaSymbol.WILD);
            }
        }
    }

    @Test
    void silverAndGoldAreNotAssignedToWildOrScatter() {
        LuckyPandaBoardGenerator boards = new LuckyPandaBoardGenerator(new Random(41001), weights());
        Random random = new Random(41002);
        int framed = 0;
        for (int i = 0; i < 80; i++) {
            LuckyPandaBoard board = boards.generate(WeightScene.PAID_START);
            LuckyPandaFrameAssigner.Frames frames = LuckyPandaFrameAssigner.assign(board, random);
            for (int coord : frames.gfl()) {
                LuckyPandaToken token = board.tokenAt(coord);
                assertTrue(LuckyPandaFrameAssigner.frameable(token));
                framed++;
            }
            for (int coord : frames.sfl()) {
                LuckyPandaToken token = board.tokenAt(coord);
                assertTrue(LuckyPandaFrameAssigner.frameable(token));
                framed++;
            }
        }
        assertTrue(framed > 0);
    }

    @Test
    void winningSilverStackBecomesGoldOnTheNextCascadePage() {
        LuckyPandaBoard board = LuckyPandaBoard.fromRskl(List.of(
                "1H5", "1H5", "1Q", "1H2", "1H3",
                "1H3", "2H5", "1H3", "1H2", "1H3",
                "1Scat", "3H5", "1T", "1A",
                "1H4", "3H4", "1H4", "1H5",
                "1H2", "3H3", "1K", "1H5",
                "1H3", "1H4", "1H1", "1H5", "1H2"));
        LuckyPandaEvaluation evaluation = LuckyPandaResultUtil.evaluate(board, BS, 1, 0);
        assertTrue(evaluation.hasWaysWin());
        assertEquals(LuckyPandaSymbol.H5, board.tokenAt(11).symbol());
        assertEquals(2, board.tokenAt(11).height());
        LuckyPandaBoardGenerator boards = new LuckyPandaBoardGenerator(new Random(11), weights());
        LuckyPandaBoardGenerator.CascadeResult next = boards.cascade(
                board, evaluation, WeightScene.CASCADE_REFILL, GameRuleCore.SCAT_TOTAL_MAX_BLOCKS, true,
                List.of(), List.of(11));
        boolean goldOnReel1 = false;
        for (int coord : next.frames().gfl()) {
            LuckyPandaToken token = next.board().tokenAt(coord);
            if (token != null && token.reel() == 1 && token.height() >= 2) goldOnReel1 = true;
        }
        assertTrue(goldOnReel1, "winning silver height-2 H5 must become gold on reel 1");
    }

    private static Map<WeightScene, int[]> weights() {
        EnumMap<WeightScene, int[]> map = new EnumMap<>(WeightScene.class);
        map.put(WeightScene.PAID_START, new int[]{652, 4254, 4182, 3987, 3995, 3897, 3962, 4003, 4075, 3966, 3882, 182, 783});
        map.put(WeightScene.CASCADE_REFILL, new int[]{287, 1181, 1125, 1045, 1001, 1034, 965, 972, 1001, 942, 899, 337, 237});
        map.put(WeightScene.FREE_START, new int[]{212, 1133, 1244, 1126, 1067, 1102, 1017, 1065, 1083, 1140, 1092, 70, 209});
        map.put(WeightScene.FREE_CASCADE_REFILL, new int[]{85, 297, 291, 283, 312, 285, 289, 274, 266, 272, 288, 46, 59});
        return map;
    }
}
