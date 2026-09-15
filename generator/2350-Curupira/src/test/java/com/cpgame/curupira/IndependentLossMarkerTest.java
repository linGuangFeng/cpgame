package com.cpgame.curupira;

import com.cpgame.curupira.codec.MinimalFactCodec;
import com.cpgame.curupira.core.*;
import com.cpgame.curupira.model.CompleteRoundFact;
import com.cpgame.curupira.model.CompleteRoundFact.Kind;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IndependentLossMarkerTest {
    @Test void ordinaryMarkerProducesFreshVerifiedZeroBoards() {
        MinimalFactCodec codec = new MinimalFactCodec();
        Set<List<Integer>> boards = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            CompleteRoundFact fact = codec.decode("CU1PL;#");
            var board = new ResultUtil().evaluate(fact.steps().get(0).cells());
            new ResultUtil().assertIndependentLoss(board);
            assertTrue(board.expandingWildColumns().isEmpty());
            assertEquals(Kind.LOSS, fact.kind());
            assertEquals(0, fact.redisMultiplier());
            assertEquals("CU1PL;#", codec.encodeFact(fact));
            assertEquals(fact.steps(), codec.decode(codec.encodeFull(fact)).steps());
            boards.add(board.ps());
        }
        assertEquals(2000, boards.size());
    }

    @Test void featureTriggerFreeAndHoldRemainFullFacts() {
        MinimalFactCodec codec = new MinimalFactCodec(); GameRuleCore core = new GameRuleCore();
        for (Kind kind : List.of(Kind.TRIGGER, Kind.FREE_EW, Kind.BUY_FE, Kind.HOLD, Kind.BUY_HS, Kind.EXPANDING_WILD, Kind.WIN)) {
            var fact = core.generateFact(kind);
            assertEquals(codec.encodeFull(fact), codec.encodeFact(fact));
            assertEquals(fact.steps(), codec.decode(codec.encodeFact(fact)).steps());
        }
        for (String member : List.of("CU1PF;#", "CU1PH;#", "CU1PT;#", "CU1BF;#", "CU1PL;#1", "CU1PL;#/S111111111111111", "#", "0"))
            assertThrows(IllegalArgumentException.class, () -> codec.decode(member));
    }
}
