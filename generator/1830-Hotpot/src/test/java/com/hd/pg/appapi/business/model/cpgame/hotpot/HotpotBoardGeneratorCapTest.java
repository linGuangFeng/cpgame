package com.hd.pg.appapi.business.model.cpgame.hotpot;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class HotpotBoardGeneratorCapTest {
    @Test
    void tenXSpecialOpeningStillRespectsCapturedScatterMaxima() {
        int[] boosted = HotpotBoardGenerator.defaultPaidStartWeights();
        boosted[HotpotResultUtil.SCATTER - 1] *= 10;
        HotpotBoardGenerator generator = new HotpotBoardGenerator(new Random(1830L), boosted,
                HotpotBoardGenerator.defaultCascadeWeights(),
                HotpotBoardGenerator.defaultFreeStartWeights());
        int sawThreeOrFour = 0;
        for (int n = 0; n < 500; n++) {
            int[] prop = generator.generate(HotpotSymbolScene.PAID_START).getProp();
            int scatter = 0;
            int[] perColumn = new int[HotpotBoard.COLUMNS];
            for (int i = 0; i < prop.length; i++) {
                if (prop[i] != HotpotResultUtil.SCATTER) continue;
                scatter++;
                perColumn[i / HotpotBoard.ROWS]++;
            }
            assertTrue(scatter <= HotpotResultUtil.MAX_SCATTER_PAID_PAGE, "scatter=" + scatter);
            for (int column = 0; column < perColumn.length; column++) {
                assertTrue(perColumn[column] <= HotpotResultUtil.MAX_SCATTER_PER_COLUMN,
                        "column " + column + " scatter=" + perColumn[column]);
            }
            if (scatter >= 3) sawThreeOrFour++;
        }
        assertTrue(sawThreeOrFour >= 8, "sawThreeOrFour=" + sawThreeOrFour);
    }
    @Test void cascadeReservesScatterSurvivorsInAllLaterColumns() {
        assertCascadeCap(HotpotSymbolScene.PAID_CASCADE, 4);
        assertCascadeCap(HotpotSymbolScene.FREE_CASCADE, 3);
    }

    private static void assertCascadeCap(HotpotSymbolScene scene, int cap) {
        int[] prop = new int[HotpotBoard.SIZE];
        for (int i = 0; i < prop.length; i++) prop[i] = 2 + i % 9;
        // Eight paying symbols open holes in the first two columns.
        for (int i = 0; i < 8; i++) prop[i] = 1;
        // The page is already at its Scatter limit, all in later columns.
        for (int col = HotpotBoard.COLUMNS - cap; col < HotpotBoard.COLUMNS; col++)
            prop[col * HotpotBoard.ROWS] = HotpotResultUtil.SCATTER;
        int[] weights = new int[HotpotBoardGenerator.SYMBOL_COUNT];
        weights[1] = 1;
        weights[HotpotResultUtil.SCATTER - 1] = 10000;
        HotpotBoardGenerator generator = new HotpotBoardGenerator(new Random(1830),
                HotpotBoardGenerator.defaultPaidStartWeights(), weights,
                HotpotBoardGenerator.defaultFreeStartWeights());
        HotpotBoard board = new HotpotBoard(prop);
        HotpotEvaluation evaluation = HotpotResultUtil.evaluate(board);
        assertEquals(HotpotPageKind.WIN, evaluation.getPageKind());
        int[] next = generator.cascade(board, evaluation, scene).getProp();
        assertEquals(cap, java.util.Arrays.stream(next).filter(v -> v == HotpotResultUtil.SCATTER).count(), scene.toString());
        for (int col = HotpotBoard.COLUMNS - cap; col < HotpotBoard.COLUMNS; col++)
            assertEquals(HotpotResultUtil.SCATTER, next[col * HotpotBoard.ROWS], "must preserve existing Scatter");
    }
}
