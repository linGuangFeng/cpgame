package com.cpgame.replica.edmmania;

import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaBoard;
import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaEvaluation;
import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaResultUtil;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 预期来自原厂夹具，不是实现自洽。 */
class FixtureOracleTest {
    @Test void ordinaryLossRound001HasNoWaysWin() {
        int[] prop = {5, 8, 5, 10, 2, 4, 4, 4, 4, 2, 7, 9, 9, 9, 5, 4, 7, 10, 10, 6, 10, 10, 3, 3, 3, 6, 7, 5, 6, 6};
        int[] trl = {3, 4, 3, 6};
        EdmManiaBoard board = new EdmManiaBoard(prop, trl,
                List.of(List.of(5, 6, 7, 8), List.of(11, 12), List.of(17, 18), List.of(20, 21), List.of(22, 23, 24)),
                List.of(),
                List.of(List.of(11, 12), List.of(17, 18), List.of(20, 21), List.of(22, 23, 24)));
        EdmManiaEvaluation ev = EdmManiaResultUtil.evaluate(board, new BigDecimal("0.20"), 1, 2);
        assertTrue(ev.getWins().isEmpty());
        assertEquals(0, ev.getTotalWin().signum());
        assertEquals(0, ev.getAwardedFreeSpins());
    }

    @Test void scatterCountsVisiblePropAndTrlAndStackedAsOne() {
        int[] prop = new int[30];
        for (int i = 0; i < 30; i++) prop[i] = 2 + (i % 10);
        prop[0] = 12; prop[5] = 12; prop[10] = 12;
        EdmManiaBoard threePlusTrl = new EdmManiaBoard(prop, new int[]{12, 3, 4, 5});
        assertEquals(4, EdmManiaResultUtil.evaluate(threePlusTrl, BigDecimal.ONE, 1, 2).getScatterCount());
        assertEquals(10, EdmManiaResultUtil.evaluate(threePlusTrl, BigDecimal.ONE, 1, 2).getAwardedFreeSpins());

        int[] stacked = new int[30];
        for (int i = 0; i < 30; i++) stacked[i] = 2 + (i % 10);
        stacked[0] = 12;
        stacked[10] = 12;
        stacked[11] = 12;
        stacked[20] = 12;
        stacked[25] = 12;
        EdmManiaBoard stackedBoard = new EdmManiaBoard(stacked, new int[]{3, 4, 5, 6},
                List.of(List.of(10, 11)), List.of(), List.of());
        assertEquals(4, EdmManiaResultUtil.evaluate(stackedBoard, BigDecimal.ONE, 1, 2).getScatterCount());
        assertEquals(10, EdmManiaResultUtil.evaluate(stackedBoard, BigDecimal.ONE, 1, 2).getAwardedFreeSpins());
    }

    @Test void survivingBallDoesNotRetriggerMultiplier() {
        int[] first = new int[30];
        for (int i = 0; i < 30; i++) first[i] = 8;
        first[0] = 9; first[5] = 9; first[10] = 9; first[11] = 1;
        EdmManiaBoard page0 = new EdmManiaBoard(first, new int[]{2, 3, 4, 5});
        EdmManiaEvaluation ev0 = EdmManiaResultUtil.evaluate(page0, BigDecimal.ONE, 1, 2, 1);
        assertEquals(2, ev0.getMultiplier());
        EdmManiaEvaluation ev1 = EdmManiaResultUtil.evaluate(page0, BigDecimal.ONE, 2, 2, 0);
        assertEquals(2, ev1.getMultiplier());
    }
}
