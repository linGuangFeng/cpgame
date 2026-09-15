package com.cpgame.curupira;

import com.cpgame.curupira.core.GameRuleCore;
import com.cpgame.curupira.core.GameRules;
import com.cpgame.curupira.core.ResultUtil;
import com.cpgame.curupira.model.Award;
import com.cpgame.curupira.model.EvaluatedBoard;
import com.cpgame.curupira.model.RoundResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    void formalRuntimeChainGeneratesFreshTerminalOrdinaryRounds() {
        GameRuleCore core = new GameRuleCore();
        ResultUtil oracle = new ResultUtil();
        BigDecimal balance = new BigDecimal("1000.00");
        Set<List<Integer>> boards = new HashSet<>();
        Set<Long> roundKeys = new HashSet<>();

        for (int i = 0; i < 100; i++) {
            RoundResult round = core.generatePaidRound(new BigDecimal("0.02"), 1, balance, 123L, "opaque");
            EvaluatedBoard independentlyChecked = oracle.evaluate(round.board().ps());
            oracle.assertOrdinaryTerminal(independentlyChecked);
            assertThat(round.totalBet()).isEqualByComparingTo("0.50");
            assertThat(round.totalWin()).isEqualByComparingTo(
                    new BigDecimal("0.02").multiply(BigDecimal.valueOf(independentlyChecked.multiplierSum())));
            assertThat(round.change()).isEqualByComparingTo(round.totalWin().subtract(round.totalBet()));
            assertThat(round.endBalance()).isEqualByComparingTo(round.startBalance().add(round.change()));
            assertThat(round.deliveryIndex()).isEqualTo(1);
            assertThat(round.terminal()).isTrue();
            assertThat(round.board().ps().subList(0, GameRules.ROWS)).doesNotContain(GameRules.WILD);
            boards.add(round.board().ps());
            roundKeys.add(round.roundKey());
            balance = round.endBalance();
        }
        assertThat(boards.size()).isGreaterThan(95);
        assertThat(roundKeys).hasSize(100);
        assertThat(roundKeys).allMatch(key -> key > 9_007_199_254_740_991L);
    }

    @Test
    void initialRoomProjectionIsRandomIndependentLossAndDoesNotCharge() {
        GameRuleCore core = new GameRuleCore();
        RoundResult room = core.generateInitialRoomProjection(new BigDecimal("1000.00"), 123L, "opaque");
        new ResultUtil().assertIndependentLoss(room.board());
        assertThat(room.paidRound()).isFalse();
        assertThat(room.deliveryIndex()).isZero();
        assertThat(room.totalBet()).isZero();
        assertThat(room.startBalance()).isEqualByComparingTo(room.endBalance());
    }
}
