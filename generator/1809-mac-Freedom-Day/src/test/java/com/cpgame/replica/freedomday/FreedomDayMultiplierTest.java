package com.cpgame.replica.freedomday;

import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayBoard;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayEvaluation;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayGridRules;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayResultUtil;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayWin;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

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

    @Test
    void capturedSevenPageCascadeDoesNotRecountSurvivingBalls() {
        CompleteRoundFact fact = new CompleteRoundCodec().decode(
                "781945555D777559999488822A7A292573Z400003005040000300200000000000000"
                        + "5819495555558559999488822A7A292536Z040002005040000300200000000000000"
                        + "1819484859C88669999488822A7A292364Z000000008040000300200000000000000"
                        + "211946245939C669999488822A7A292364Z000000008040000300200000000000000"
                        + "A21146624543C6688B3488822A7A292364Z000000008020000300200000000000000"
                        + "6A2116662593C66A88B388822A7A29236AZ000000008002000300200000000000000"
                        + "6A211A672593C44A88B388822A7A2923A2Z000000002002000300200000000000000");
        int[] expected = {2, 2, 4, 4, 4, 4, 4};
        int multiplier = 1;
        FreedomDayBoard previous = null;
        FreedomDayEvaluation previousEval = null;
        List<CompleteRoundFact.BoardFact> pages = fact.spins().get(0);
        assertEquals(expected.length, pages.size());
        for (int i = 0; i < pages.size(); i++) {
            CompleteRoundFact.BoardFact page = pages.get(i);
            FreedomDayBoard board = new FreedomDayBoard(
                    page.prop().stream().mapToInt(Integer::intValue).toArray(),
                    page.trl().stream().mapToInt(Integer::intValue).toArray(),
                    page.grids(), page.gf(), page.sl());
            int newBalls = FreedomDayGridRules.countNewBalls(previous, previousEval, board);
            FreedomDayEvaluation evaluation = FreedomDayResultUtil.evaluate(
                    board, BigDecimal.ONE, multiplier, 2, newBalls);
            assertEquals(expected[i], evaluation.getMultiplier(), "page " + i);
            previous = board;
            previousEval = evaluation;
            multiplier = evaluation.getMultiplier();
        }
        assertEquals(4, multiplier);
        assertEquals(320, new CompleteRoundCodec().verify(fact, 10, 30).multiplier().intValue());
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
