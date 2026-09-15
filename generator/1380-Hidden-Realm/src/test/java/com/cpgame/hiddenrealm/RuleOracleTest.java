package com.cpgame.hiddenrealm;

import com.cpgame.hiddenrealm.core.CompleteRound;
import com.cpgame.hiddenrealm.core.GameRuleCore;
import com.cpgame.hiddenrealm.core.ResultUtil;
import com.cpgame.hiddenrealm.core.RoundCodec;
import com.cpgame.hiddenrealm.core.RoundGenerator;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuleOracleTest {
    private final GameRuleCore rules = new GameRuleCore();
    private final ResultUtil oracle = new ResultUtil(rules);

    @Test
    void wildClusterMatchesCapturedRound9Page0() {
        int[][] board = {
                {6, 5, 9, 1, 4},
                {2, 6, 9, 6, 7},
                {7, 3, 6, 3, 3},
                {1, 8, 8, 3, 4},
                {2, 8, 7, 7, 8}
        };
        GameRuleCore.Evaluation ev = rules.evaluate(board);
        assertEquals(1, ev.clusters().size());
        assertEquals(6, ev.clusters().get(0).symbol());
        assertEquals(5, ev.clusters().get(0).cells().size());
        assertEquals(20, ev.clusters().get(0).odds());
        assertEquals(20, oracle.clusterOdds(board));
    }

    @Test
    void lordPageWithLowsDoesNotScore() {
        int[][] board = {
                {8, 6, 6, 6, 5},
                {7, 6, 4, 6, 5},
                {8, 6, 6, 8, 2},
                {7, 6, 5, 5, 7},
                {5, 8, 8, 5, 5}
        };
        assertTrue(rules.hasLow(board));
        assertEquals(0, rules.evaluatePage(board, 4).oddsSum());
        assertTrue(rules.evaluate(board).oddsSum() > 0);
        assertEquals(0, oracle.pageOdds(board, 4));
    }

    @Test
    void waterWritesFourFixedWilds() {
        int[][] board = {
                {1, 2, 3, 4, 5},
                {5, 6, 7, 8, 1},
                {2, 3, 4, 5, 6},
                {6, 7, 8, 1, 2},
                {3, 4, 5, 6, 7}
        };
        int[][] water = rules.applyWater(board);
        assertEquals(9, water[1][1]);
        assertEquals(9, water[1][3]);
        assertEquals(9, water[3][1]);
        assertEquals(9, water[3][3]);
    }

    @Test
    void codecRoundtripAndIndependentOracle() {
        RoundGenerator generator = new RoundGenerator(new SecureRandom(), rules);
        RoundCodec codec = new RoundCodec();
        for (int i = 0; i < 12; i++) {
            CompleteRound round = i < 4 ? generator.ordinary(false) : i < 8 ? generator.ordinary(true) : generator.special(1);
            rules.validateRound(round);
            assertEquals(codec.encode(round), codec.encode(codec.decode(codec.encode(round))));
            assertEquals(round.totalOdds(), oracle.analyze(round).oddsSum());
            assertFalse(codec.encode(round).startsWith("{"));
        }
    }
}
