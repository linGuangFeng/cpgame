package com.cpgame.curupira;

import com.cpgame.curupira.codec.MinimalFactCodec;
import com.cpgame.curupira.core.GameRuleCore;
import com.cpgame.curupira.core.GameRules;
import com.cpgame.curupira.core.GenerationPolicy;
import com.cpgame.curupira.core.ResultUtil;
import com.cpgame.curupira.model.CompleteRoundFact;
import com.cpgame.curupira.model.CompleteRoundFact.Kind;
import com.cpgame.curupira.model.FeatureStep;
import com.cpgame.curupira.redis.RedisContractGate;
import org.junit.jupiter.api.Test;
import java.util.EnumSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SpecialModeAndCodecTest {
    @Test
    void redisKeysFollowPlatformContract() {
        RedisContractGate keys = new RedisContractGate();
        assertEquals("PerKeyList_000002350", keys.normalIndex(2350));
        assertEquals("MaryKeyList_000002350", keys.specialIndex(2350));
        assertEquals("BetLog:000002350:000000", keys.resultKey("ORDINARY_PAID", 0, 2350));
        assertEquals("BetLog:000002350:000050", keys.resultKey("ORDINARY_PAID", 50, 2350));
        assertEquals("MaryLog:000002350:000375", keys.resultKey("SPECIAL", 375, 2350));
        assertEquals("PerKeyList_000002350", keys.indexFor(Kind.LOSS, 2350));
        assertEquals("PerKeyList_000002350", keys.indexFor(Kind.EXPANDING_WILD, 2350));
        assertEquals("MaryKeyList_000002350", keys.indexFor(Kind.FREE_EW, 2350));
        assertEquals("MaryKeyList_100002350", keys.indexFor(Kind.HOLD, 2350));
        assertEquals("MaryKeyList_000002350", keys.indexFor(Kind.BUY_FE, 2350));
        assertEquals("MaryKeyList_100002350", keys.indexFor(Kind.BUY_HS, 2350));
        assertEquals("MaryLog:000002350:000010", keys.listFor(Kind.FREE_EW, 10, 2350));
        assertEquals("MaryLog:100002350:000010", keys.listFor(Kind.HOLD, 10, 2350));
        assertEquals(List.of("MaryKeyList_000002350"), keys.indexesToWrite(Kind.FREE_EW, 2350));
        assertEquals(List.of("MaryKeyList_100002350", "PerKeyList_100002350"), keys.indexesToWrite(Kind.HOLD, 2350));
        assertNotEquals(keys.indexFor(Kind.FREE_EW, 2350), keys.indexFor(Kind.HOLD, 2350));
        assertThrows(IllegalArgumentException.class, () -> keys.indexFor(Kind.TRIGGER, 2350));
        assertThrows(IllegalArgumentException.class, () -> keys.indexesToWrite(Kind.TRIGGER, 2350));
    }

    @Test
    void liveTriggerIsNonWinningScatterBoard() {
        GameRuleCore core = new GameRuleCore(new DeterministicRandomSource(2350), GenerationPolicy.ordinaryPaidDefaults());
        ResultUtil util = new ResultUtil();
        for (int i = 0; i < 20; i++) {
            CompleteRoundFact trigger = core.generateFact(Kind.TRIGGER);
            var board = util.evaluate(trigger.steps().get(0).cells());
            assertEquals(GameRules.SCATTER_TRIGGER, board.scatterCount());
            assertTrue(board.awards().isEmpty());
            assertEquals(0, board.multiplierSum());
            assertEquals(0, trigger.redisMultiplier());
        }
    }

    @Test
    void asciiMemberRoundTripsEveryKind() {
        GameRuleCore core = new GameRuleCore(new DeterministicRandomSource(235020), GenerationPolicy.ordinaryPaidDefaults());
        MinimalFactCodec codec = new MinimalFactCodec();
        ResultUtil util = new ResultUtil();
        for (Kind kind : Kind.values()) {
            CompleteRoundFact fact = core.generateFact(kind);
            String member = codec.encodeFact(fact);
            assertFalse(member.startsWith("{"));
            assertTrue(member.startsWith("CU1"));
            CompleteRoundFact decoded = codec.decode(member);
            assertEquals(kind, decoded.kind());
            assertEquals(fact.steps().size(), decoded.steps().size());
            assertEquals(util.redisMultiplier(fact), util.redisMultiplier(decoded));
        }
    }

    @Test
    void freeAndHoldTerminateAndTriggerHasThreeScatters() {
        GameRuleCore core = new GameRuleCore(new DeterministicRandomSource(42), GenerationPolicy.ordinaryPaidDefaults());
        CompleteRoundFact free = core.generateFact(Kind.FREE_EW);
        assertEquals(6, free.steps().size());
        assertEquals(0, free.steps().get(5).st());
        for (FeatureStep step : free.steps()) {
            assertEquals(1, new ResultUtil().evaluate(step.cells()).expandingWildColumns().size());
        }
        CompleteRoundFact hold = core.generateFact(Kind.HOLD);
        assertEquals(0, hold.steps().get(hold.steps().size() - 1).st());
        CompleteRoundFact trigger = core.generateFact(Kind.TRIGGER);
        assertEquals(GameRules.SCATTER_TRIGGER, new ResultUtil().evaluate(trigger.steps().get(0).cells()).scatterCount());
        assertEquals(EnumSet.of(Kind.BUY_FE), EnumSet.of(core.generateFact(Kind.BUY_FE).kind()));
        assertEquals(Kind.BUY_HS, core.generateFact(Kind.BUY_HS).kind());
    }

    @Test
    void boardsNeverPlaceTwoScattersInOneColumn() {
        GameRuleCore core = new GameRuleCore(new DeterministicRandomSource(7), GenerationPolicy.ordinaryPaidDefaults());
        for (int i = 0; i < 200; i++) {
            for (Kind kind : List.of(Kind.LOSS, Kind.WIN, Kind.EXPANDING_WILD, Kind.TRIGGER, Kind.FREE_EW)) {
                CompleteRoundFact fact = kind == Kind.WIN ? core.generateWinRange(1, 10_000) : core.generateFact(kind);
                for (FeatureStep step : fact.steps()) {
                    if (step.role() == FeatureStep.Role.HOLD) continue;
                    int[] perCol = new int[GameRules.COLUMNS];
                    List<Integer> cells = step.cells();
                    for (int idx = 0; idx < cells.size(); idx++) {
                        if (cells.get(idx) == GameRules.SCATTER) perCol[idx / GameRules.ROWS]++;
                    }
                    for (int c = 0; c < perCol.length; c++) {
                        assertTrue(perCol[c] <= 1, "column " + c + " has " + perCol[c] + " scatters");
                    }
                }
            }
        }
    }
}
