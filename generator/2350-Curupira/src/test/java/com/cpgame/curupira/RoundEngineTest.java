package com.cpgame.curupira;

import com.cpgame.curupira.config.EngineConfiguration;
import com.cpgame.curupira.core.GameRuleCore;
import com.cpgame.curupira.core.GameRules;
import com.cpgame.curupira.core.ResultUtil;
import com.cpgame.curupira.model.Award;
import com.cpgame.curupira.model.CompleteRoundFact;
import com.cpgame.curupira.model.EvaluatedBoard;
import com.cpgame.curupira.verify.RoundVerifier;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoundEngineTest {
    private static final Path FORMAL = Path.of("packaging/generator.properties");

    @Test void capturedRoundTenIsIndependentOracle() {
        EvaluatedBoard board = new ResultUtil().evaluate(List.of(1,1,21,1,21,14,21,21,21,1,1,3,2,12,4));
        assertEquals(1303, board.multiplierSum());
        assertEquals(List.of(2), board.expandingWildColumns());
        assertEquals(20, board.awards().size());
    }

    @Test void visualPaylineRowsAreMappedBackToWireRows() {
        EvaluatedBoard board = new ResultUtil().evaluate(List.of(13,14,4,13,12,12,2,2,21,21,14,21,13,2,12));
        assertEquals(8, board.multiplierSum());
        assertEquals(List.of(new Award(5,18,8,13)), board.awards());
    }

    @Test void oneCallDrawsOneFactThenClassifiesIt() throws Exception {
        EngineConfiguration config = EngineConfiguration.load(FORMAL);
        GameRuleCore core = new GameRuleCore(new DeterministicRandomSource(2350), config.generationPolicy());
        RoundVerifier verifier = new RoundVerifier();
        ResultUtil oracle = new ResultUtil();
        Set<List<Integer>> boards = new HashSet<>();
        boolean firstReelWildObserved = false;
        for (int i = 0; i < 5_000; i++) {
            CompleteRoundFact fact = core.generatePaidCandidate();
            verifier.verifyFact(fact);
            EvaluatedBoard evaluated = oracle.evaluate(fact.steps().get(0).cells());
            assertEquals(oracle.classifyPaid(evaluated), fact.kind());
            assertTrue(GameRules.hasAtMostOneScatterPerColumn(evaluated.ps()));
            firstReelWildObserved |= evaluated.ps().subList(0, GameRules.ROWS).contains(GameRules.WILD);
            boards.add(evaluated.ps());
        }
        assertTrue(firstReelWildObserved, "首轴 Wild 没有证据证明禁出，生成器不得硬禁");
        assertTrue(boards.size() > 4_950);
    }

    @Test void formalBaseWeightsAreTheRecordedTenRoundCounts() throws Exception {
        assertEquals(java.util.Map.of(1,26,2,14,3,16,4,16,11,16,12,17,13,19,14,17,21,6,31,3),
                EngineConfiguration.load(FORMAL).generationPolicy().symbolWeights());
    }
}
