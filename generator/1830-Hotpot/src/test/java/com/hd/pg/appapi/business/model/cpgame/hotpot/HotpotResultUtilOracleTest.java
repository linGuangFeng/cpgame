package com.hd.pg.appapi.business.model.cpgame.hotpot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HotpotResultUtilOracleTest {
    /** captures/1830-Hotpot/history-detail-ORDINARY_WIN.json result[0] */
    private static final int[] PAGE0 = {
            1, 8, 8, 8, 8, 11,
            4, 1, 6, 6, 2, 2,
            4, 4, 2, 2, 8, 5,
            7, 3, 4, 4, 8, 8,
            1, 1, 13, 1, 1, 8,
            4, 7, 7, 5, 2, 1
    };
    /** same file result[1] */
    private static final int[] PAGE1 = {
            12, 5, 4, 7, 1, 11,
            4, 1, 6, 6, 2, 2,
            1, 4, 4, 2, 2, 5,
            4, 1, 7, 3, 4, 4,
            5, 1, 1, 13, 1, 1,
            4, 7, 7, 5, 2, 1
    };
    /** last page of that spin, multipliers at 4/9/29 */
    private static final int[] LAST = {
            6, 4, 3, 6, 12, 11,
            8, 7, 4, 13, 6, 6,
            3, 4, 3, 2, 1, 5,
            9, 10, 7, 9, 1, 3,
            10, 6, 6, 1, 1, 13,
            1, 1, 9, 3, 6, 10
    };

    @Test void countAnywhereMatchesHistoryPage0() {
        HotpotEvaluation evaluation = HotpotResultUtil.evaluate(new HotpotBoard(PAGE0));
        assertEquals(HotpotPageKind.WIN, evaluation.getPageKind());
        assertEquals(1, evaluation.getWins().size());
        HotpotWin win = evaluation.getWins().get(0);
        assertEquals(8, win.getSymbol());
        assertEquals(8, win.getCount());
        assertEquals(8, win.getOdd());
        assertEquals(8, evaluation.getOddSum());
        assertEquals(1, evaluation.getScatterCount());
    }

    @Test void page1MatchesHistoryWinArr() {
        HotpotEvaluation evaluation = HotpotResultUtil.evaluate(new HotpotBoard(PAGE1));
        assertEquals(2, evaluation.getWins().size());
        HotpotWin fours = evaluation.getWins().stream().filter(w -> w.getSymbol() == 4).findFirst().orElseThrow();
        HotpotWin ones = evaluation.getWins().stream().filter(w -> w.getSymbol() == 1).findFirst().orElseThrow();
        assertEquals(8, fours.getCount());
        assertEquals(20, fours.getOdd());
        assertEquals(9, ones.getCount());
        assertEquals(60, ones.getOdd());
        assertEquals(80, evaluation.getOddSum());
    }

    @Test void cascadeSurvivorsFromPage0MatchPage1() {
        HotpotBoard first = new HotpotBoard(PAGE0);
        HotpotEvaluation evaluation = HotpotResultUtil.evaluate(first);
        boolean[] removed = HotpotResultUtil.eliminatedMask(first, evaluation);
        int[] before = first.getProp();
        int[] after = PAGE1;
        for (int col = 0; col < 6; col++) {
            int[] keep = new int[6];
            int kept = 0;
            for (int row = 0; row < 6; row++) {
                int index = col * 6 + row;
                if (!removed[index]) keep[kept++] = before[index];
            }
            int fill = 6 - kept;
            for (int i = 0; i < kept; i++) {
                assertEquals(keep[i], after[col * 6 + fill + i], "col=" + col + " survivor " + i);
            }
        }
    }

    @Test void lastPageMultipliersSumToEightAndScaleSpin() {
        HotpotEvaluation last = HotpotResultUtil.evaluate(new HotpotBoard(LAST));
        assertEquals(HotpotPageKind.TERMINAL_NO_WIN, last.getPageKind());
        assertEquals(8, last.getMultiplierSum());
        assertEquals(0, last.getOddSum());
        int oddUnits = 8 + 20 + 60 + 9 + 25 + 50;
        assertEquals(172, oddUnits);
        assertEquals(1376, oddUnits * last.getMultiplierSum());
    }

    @Test void scatterAwardsAreModePartitioned() {
        assertEquals(10, HotpotResultUtil.awardedFreeSpins(3, HotpotSpinMode.PAID));
        assertEquals(12, HotpotResultUtil.awardedFreeSpins(4, HotpotSpinMode.PAID));
        assertEquals(0, HotpotResultUtil.awardedFreeSpins(2, HotpotSpinMode.PAID));
        assertEquals(5, HotpotResultUtil.awardedFreeSpins(2, HotpotSpinMode.FREE));
        assertEquals(7, HotpotResultUtil.awardedFreeSpins(3, HotpotSpinMode.FREE));
        assertEquals(0, HotpotResultUtil.awardedFreeSpins(1, HotpotSpinMode.FREE));
    }

    @Test void spinIntegerMultiplierRequiresTerminalNoWin() {
        HotpotBoard win = new HotpotBoard(PAGE0);
        assertThrows(IllegalArgumentException.class, () -> HotpotResultUtil.spinIntegerMultiplier(List.of(win)));
        HotpotBoard terminal = new HotpotIndependentLossGenerator().generate(new java.util.Random(1830L));
        assertEquals(0, HotpotResultUtil.spinIntegerMultiplier(List.of(terminal)));
    }
}
