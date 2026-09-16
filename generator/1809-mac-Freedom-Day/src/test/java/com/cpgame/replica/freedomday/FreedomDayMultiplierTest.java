package com.cpgame.replica.freedomday;

import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayBoard;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayEvaluation;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayResultUtil;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayWin;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreedomDayMultiplierTest {
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

        FreedomDayEvaluation result = FreedomDayResultUtil.evaluate(
                new FreedomDayBoard(prop, new int[]{2, 3, 4, 5}), new BigDecimal("0.01"), 1, 2);
        FreedomDayWin win = result.getWins().stream()
                .filter(value -> value.getSymbol() == 9)
                .findFirst()
                .orElseThrow(AssertionError::new);

        assertEquals(6, result.getMultiplier());
        assertEquals(6, win.getMultiplier());
        assertEquals(new BigDecimal("0.06"), win.getWinMoney());
    }

    @Test
    void noWinPageStillCollectsVisibleBallsForFollowingFreeSpins() {
        int[] prop = noWinBoard();
        prop[2] = 1;

        FreedomDayEvaluation result = FreedomDayResultUtil.evaluate(
                new FreedomDayBoard(prop, new int[]{2, 3, 4, 5}), BigDecimal.ONE, 4, 2);

        assertTrue(result.getWins().isEmpty());
        assertEquals(6, result.getMultiplier());
        assertEquals(0, result.getTotalWin().signum());
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
