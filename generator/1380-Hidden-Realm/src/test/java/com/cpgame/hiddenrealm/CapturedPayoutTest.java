package com.cpgame.hiddenrealm;

import com.cpgame.hiddenrealm.core.GameRuleCore;
import com.cpgame.hiddenrealm.core.ResultUtil;
import com.cpgame.hiddenrealm.loader.OriginHoldout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CapturedPayoutTest {
    @Test
    void tenSymbol8ClusterPays500Odds() {
        int[][] board = {
                {8, 8, 1, 1, 8},
                {8, 8, 7, 6, 4},
                {5, 8, 8, 3, 6},
                {7, 8, 8, 5, 3},
                {5, 8, 8, 6, 2}
        };
        GameRuleCore rules = new GameRuleCore();
        ResultUtil oracle = new ResultUtil(rules);
        GameRuleCore.Evaluation ev = rules.evaluate(board);
        assertEquals(1, ev.clusters().size());
        assertEquals(8, ev.clusters().get(0).symbol());
        assertEquals(10, ev.clusters().get(0).cells().size());
        assertEquals(500, ev.clusters().get(0).odds());
        assertEquals(500, oracle.clusterOdds(board));
    }

    @Test
    void originHoldoutAgreesWithIndependentOracle() throws Exception {
        GameRuleCore rules = new GameRuleCore();
        OriginHoldout.check(rules, new ResultUtil(rules));
    }
}
