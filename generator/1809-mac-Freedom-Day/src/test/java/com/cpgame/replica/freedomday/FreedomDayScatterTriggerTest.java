package com.cpgame.replica.freedomday;

import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayBoard;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayBoardGenerator;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayEvaluation;
import com.hd.pg.appapi.business.vo.cpgame.freedomday.FreedomDayResultUtil;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;

import static com.cpgame.replica.freedomday.FreedomDayIndependentLossGeneratorTest.assertTriggerLayout;
import static org.junit.jupiter.api.Assertions.*;

class FreedomDayScatterTriggerTest {
    @Test void stackedScatterCountsAsOneVisibleTrigger() {
        int[] prop = noWinBoard();
        prop[5] = 12;
        prop[6] = 12;
        prop[0] = 12;
        prop[10] = 12;
        prop[15] = 12;
        FreedomDayBoard board = new FreedomDayBoard(prop, new int[]{2, 3, 4, 5},
                List.of(List.of(5, 6)), List.of(), List.of());
        FreedomDayEvaluation evaluation = FreedomDayResultUtil.evaluate(board, BigDecimal.ONE, 1, 2);
        assertEquals(4, evaluation.getScatterCount());
        assertEquals(10, evaluation.getAwardedFreeSpins());
        assertTrue(evaluation.getWins().isEmpty());
        assertEquals(List.of(5, 6), board.getGrids().get(0));
    }

    @Test void generatorKeepsOneTriggerPerReelAndTopStrip() {
        FreedomDayBoardGenerator generator = new FreedomDayBoardGenerator(new Random(18092260L));
        int stackedScatter = 0;
        int triggerBoards = 0;
        for (int i = 0; i < 2_000; i++) {
            FreedomDayBoard board = generator.generate((i & 1) == 1, i % 5 == 0);
            assertTriggerLayout(board);
            FreedomDayEvaluation evaluation = FreedomDayResultUtil.evaluate(board, BigDecimal.ONE, 1, 2);
            if (evaluation.getScatterCount() >= 4) {
                triggerBoards++;
                assertTrue(evaluation.getWins().isEmpty(), "trigger boards must not win");
                assertEquals(0, evaluation.getTotalMultiplier().signum());
            }
            for (List<Integer> group : board.getGrids()) {
                if (board.getProp()[group.get(0)] == 12) {
                    stackedScatter++;
                    assertTrue(group.size() >= 2 && group.size() <= 4);
                    assertTrue(board.getGoldFrames().stream().noneMatch(group::equals));
                    assertTrue(board.getSilverFrames().stream().noneMatch(group::equals));
                }
            }
        }
        assertTrue(stackedScatter > 0, "long-frame Scatter should remain supported");
        assertTrue(triggerBoards > 0, "special/free weights must still produce trigger boards");
    }

    @Test void featureTriggerHasFourVisibleScattersAndNoWin() {
        FreedomDayBoardGenerator generator = new FreedomDayBoardGenerator(new Random(2260L));
        for (int i = 0; i < 200; i++) {
            FreedomDayBoard board = generator.generateFeatureTrigger();
            assertTriggerLayout(board);
            FreedomDayEvaluation evaluation = FreedomDayResultUtil.evaluate(board, BigDecimal.ONE, 1, 2);
            assertEquals(4, evaluation.getScatterCount());
            assertEquals(10, evaluation.getAwardedFreeSpins());
            assertTrue(evaluation.getWins().isEmpty());
            assertEquals(0, evaluation.getTotalMultiplier().signum());
            int top = 0;
            for (int symbol : board.getTrl()) if (symbol == 12) top++;
            assertTrue(top <= 1);
        }
    }

    @Test void featureBuyRoundOpeningSpinDoesNotWin() {
        CompleteRoundFactory factory = new CompleteRoundFactory();
        for (int i = 0; i < 30; i++) {
            CompleteRoundFactory.GeneratedRound round = factory.generate(new Random(1809L + i), true, 10);
            CompleteRoundFact.BoardFact opening = round.fact().spins().get(0).get(0);
            FreedomDayBoard board = new FreedomDayBoard(
                    opening.prop().stream().mapToInt(Integer::intValue).toArray(),
                    opening.trl().stream().mapToInt(Integer::intValue).toArray(),
                    opening.grids(), opening.gf(), opening.sl());
            FreedomDayEvaluation evaluation = FreedomDayResultUtil.evaluate(board, BigDecimal.ONE, 1, 2);
            assertTrue(evaluation.getWins().isEmpty());
            assertEquals(4, evaluation.getScatterCount());
            assertEquals(1, round.fact().spins().get(0).size());
            assertTrue(round.fact().spins().size() >= 11);
        }
    }

    private static int[] noWinBoard() {
        int[] prop = new int[30];
        for (int reel = 0; reel < 6; reel++) {
            for (int row = 0; row < 5; row++) {
                prop[reel * 5 + row] = 2 + (reel + row * 3) % 10;
            }
        }
        return prop;
    }
}
