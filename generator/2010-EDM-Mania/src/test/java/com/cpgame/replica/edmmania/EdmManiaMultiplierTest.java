package com.cpgame.replica.edmmania;

import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaBoard;
import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaEvaluation;
import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaResultUtil;
import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaWin;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdmManiaMultiplierTest {
    @Test
    void normalWinningPageCollectsEachBallAsPlusTwo() {
        int[] prop = noWinBoard();
        removeExtraSymbol(prop, 9, 3);
        prop[0] = 9;
        prop[5] = 9;
        prop[10] = 9;
        prop[2] = 1;
        prop[17] = 1;
        prop[29] = 1;

        EdmManiaEvaluation result = EdmManiaResultUtil.evaluate(
                new EdmManiaBoard(prop, new int[]{2, 3, 4, 5}), new BigDecimal("0.01"), 1, 2);
        EdmManiaWin win = result.getWins().stream()
                .filter(value -> value.getSymbol() == 9)
                .findFirst()
                .orElseThrow(AssertionError::new);

        assertEquals(6, result.getMultiplier());
        assertEquals(6, win.getMultiplier());
        assertEquals(new BigDecimal("0.06"), win.getWinMoney());
    }

    @Test
    void survivingBallsWithZeroNewCountDoNotRetriggerMultiplier() {
        int[] prop = noWinBoard();
        prop[2] = 1;
        prop[17] = 1;
        prop[29] = 1;

        EdmManiaEvaluation result = EdmManiaResultUtil.evaluate(
                new EdmManiaBoard(prop, new int[]{2, 3, 4, 5}), BigDecimal.ONE, 4, 2, 0);

        assertTrue(result.getWins().isEmpty());
        assertEquals(4, result.getMultiplier());
        assertEquals(0, result.getTotalWin().signum());
    }

    @Test
    void newVisibleMainBallsAddMultiplierEvenWithoutWaysWin() {
        int[] prop = noWinBoard();
        prop[2] = 1;
        EdmManiaEvaluation result = EdmManiaResultUtil.evaluate(
                new EdmManiaBoard(prop, new int[]{2, 3, 4, 5}), BigDecimal.ONE, 1, 2, 1);
        assertTrue(result.getWins().isEmpty());
        assertEquals(2, result.getMultiplier());
    }

    @Test
    void stackedBallsCountAsOneVisibleBall() {
        int[] prop = noWinBoard();
        prop[10] = 1;
        prop[11] = 1;
        prop[12] = 1;
        prop[13] = 1;
        prop[23] = 1;
        EdmManiaBoard board = new EdmManiaBoard(prop, new int[]{2, 3, 4, 5},
                List.of(List.of(10, 11, 12, 13)), List.of(), List.of());
        assertEquals(2, EdmManiaResultUtil.countVisibleMainBalls(board));
        EdmManiaEvaluation result = EdmManiaResultUtil.evaluate(board, BigDecimal.ONE, 1, 2);
        assertEquals(4, result.getMultiplier());
    }

    private int[] noWinBoard() {
        int[] prop = new int[30];
        for (int reel = 0; reel < 6; reel++) {
            for (int row = 0; row < 5; row++) {
                prop[reel * 5 + row] = 2 + (reel + row * 3) % 10;
            }
        }
        return prop;
    }

    private void removeExtraSymbol(int[] prop, int symbol, int reelCount) {
        for (int reel = 0; reel < reelCount; reel++) {
            for (int row = 0; row < 5; row++) {
                int index = reel * 5 + row;
                if (prop[index] == symbol) prop[index] = 8;
            }
        }
    }
}
