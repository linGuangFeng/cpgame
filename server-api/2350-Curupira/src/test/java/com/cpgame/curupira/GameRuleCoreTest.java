package com.cpgame.curupira;

import com.cpgame.curupira.core.GameRuleCore;
import com.cpgame.curupira.core.GameRules;
import com.cpgame.curupira.core.ResultUtil;
import com.cpgame.curupira.model.Award;
import com.cpgame.curupira.model.CompleteRoundFact;
import com.cpgame.curupira.model.EvaluatedBoard;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GameRuleCoreTest {
    @Test
    void independentOracleReproducesCapturedRoundTenRules() {
        List<Integer> board = List.of(1,1,21, 1,21,14, 21,21,21, 1,1,3, 2,12,4);
        EvaluatedBoard evaluated = new ResultUtil().evaluate(board);

        assertThat(evaluated.multiplierSum()).isEqualTo(1303);
        assertThat(new BigDecimal("0.02").multiply(BigDecimal.valueOf(evaluated.multiplierSum())))
                .isEqualByComparingTo("26.06");
        assertThat(evaluated.awards()).hasSize(20);
        assertThat(evaluated.expandingWildColumns()).containsExactly(2);
        assertThat(evaluated.scatterCount()).isZero();
    }

    @Test
    void visualLineNumberAndHighlightedSymbolsUseFrontendRowCoordinates() {
        List<Integer> board = List.of(13,14,4, 13,12,12, 2,2,21, 21,14,21, 13,2,12);
        EvaluatedBoard evaluated = new ResultUtil().evaluate(board);

        assertThat(evaluated.awards()).containsExactly(new Award(5, 18, 8, 13));
        assertThat(new BigDecimal("0.02").multiply(BigDecimal.valueOf(evaluated.multiplierSum())))
                .isEqualByComparingTo("0.16");
    }

    @Test
    void coreDrawsOneNaturalCandidateThenClassifiesTheFact() {
        GameRuleCore core = new GameRuleCore();
        ResultUtil oracle = new ResultUtil();
        for (int i = 0; i < 100; i++) {
            CompleteRoundFact fact = core.generatePaidCandidate();
            EvaluatedBoard independentlyChecked = oracle.evaluate(fact.steps().get(0).cells());
            assertThat(fact.kind()).isEqualTo(oracle.classifyPaid(independentlyChecked));
            assertThat(GameRules.hasAtMostOneScatterPerColumn(independentlyChecked.ps())).isTrue();
            assertThat(fact.roundKey()).isGreaterThan(9_007_199_254_740_991L);
        }
    }

    @Test
    void moneyProjectionRemainsAFormattingUtilityOnly() {
        assertThat(GameRuleCore.money(new BigDecimal("1.235"))).isEqualByComparingTo("1.24");
    }
}
