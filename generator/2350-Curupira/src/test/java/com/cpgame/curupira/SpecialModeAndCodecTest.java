package com.cpgame.curupira;

import com.cpgame.curupira.codec.MinimalFactCodec;
import com.cpgame.curupira.core.GameRuleCore;
import com.cpgame.curupira.core.GameRules;
import com.cpgame.curupira.core.GenerationPolicy;
import com.cpgame.curupira.core.ResultUtil;
import com.cpgame.curupira.model.CompleteRoundFact;
import com.cpgame.curupira.model.CompleteRoundFact.EntryKind;
import com.cpgame.curupira.model.CompleteRoundFact.Kind;
import com.cpgame.curupira.model.FeatureStep;
import com.cpgame.curupira.redis.RedisContractGate;
import com.cpgame.curupira.verify.RoundVerifier;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpecialModeAndCodecTest {
    @Test void redisKeysKeepNormalPer0AndTwoStableMaryTypes() {
        RedisContractGate keys = new RedisContractGate();
        assertEquals("PerKeyList_000002350", keys.normalIndex(2350));
        assertEquals("PerKeyList_000002350", keys.indexFor(Kind.TRIGGER, 2350));
        assertEquals("MaryKeyList_000002350", keys.indexFor(Kind.FREE_EW, 2350));
        assertEquals("MaryKeyList_100002350", keys.indexFor(Kind.HOLD, 2350));
        assertEquals("BetLog:000002350:000000", keys.listFor(Kind.TRIGGER, 0, 2350));
        assertEquals("MaryLog:000002350:000050", keys.listFor(Kind.FREE_EW, 50, 2350));
        assertEquals("MaryLog:100002350:000125", keys.listFor(Kind.HOLD, 125, 2350));
        assertEquals(3, keys.allIndexKeys(2350).size());
    }

    @Test void ordinaryAndTriggerFullMembersRoundTrip() {
        GameRuleCore core = core(235020);
        MinimalFactCodec codec = new MinimalFactCodec();
        RoundVerifier verifier = new RoundVerifier();
        boolean triggerObserved = false;
        for (int index = 0; index < 5_000; index++) {
            CompleteRoundFact fact = core.generatePaidCandidate();
            String member = codec.encodeFact(fact);
            CompleteRoundFact decoded = codec.decode(member);
            verifier.verifyFact(decoded);
            assertEquals(fact.kind(), decoded.kind());
            assertEquals(fact.steps(), decoded.steps());
            assertSeparatorLimit(member);
            triggerObserved |= fact.kind() == Kind.TRIGGER;
        }
        assertTrue(triggerObserved, "自然付费候选应能到达 Scatter trigger");
    }

    @Test void bothMaryKindsAreNaturalCompleteFactsAndCompact() {
        GameRuleCore core = core(235021);
        MinimalFactCodec codec = new MinimalFactCodec();
        RoundVerifier verifier = new RoundVerifier();
        for (int index = 0; index < 200; index++) {
            CompleteRoundFact free = core.generateFreeExpandingWildCandidate();
            CompleteRoundFact hold = core.generateHoldAndSpinsCandidate();
            assertEquals(Kind.FREE_EW, free.kind());
            assertEquals(GameRules.FREE_EXPANDING_WILD_COUNT, free.steps().size());
            assertEquals(Kind.HOLD, hold.kind());
            assertTrue(hold.steps().size() <= GameRules.HOLD_START_SPINS + GameRules.COIN_TOTAL_COUNT);
            for (CompleteRoundFact fact : List.of(free, hold)) {
                String member = codec.encodeFact(fact);
                CompleteRoundFact decoded = codec.decode(member);
                verifier.verifyFact(decoded);
                assertEquals(fact.kind(), decoded.kind());
                assertEquals(fact.steps(), decoded.steps());
                assertSeparatorLimit(member);
            }
        }
    }

    @Test void sameColumnDoubleScatterIsRejectedByIndependentRules() {
        List<Integer> invalid = List.of(
                31,31,1,
                2,3,4,
                11,12,13,
                14,1,2,
                3,4,11);
        assertFalse(GameRules.hasAtMostOneScatterPerColumn(invalid));
        assertThrows(IllegalArgumentException.class, () -> new ResultUtil().evaluate(invalid));
    }

    @Test void manuallyEncodedTriggerMustCarryTheConfirmedSelectionState() {
        List<Integer> cells = List.of(31,1,2, 31,3,4, 31,11,12, 13,14,1, 2,3,4);
        var evaluated = new ResultUtil().evaluate(cells);
        CompleteRoundFact invalid = new CompleteRoundFact(10_000_000_000_000_001L, Kind.TRIGGER,
                EntryKind.PAID, List.of(FeatureStep.symbol(FeatureStep.Role.TRIGGER,
                cells, evaluated, 0, 0, 0, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> new RoundVerifier().verifyFact(invalid));
    }

    private static GameRuleCore core(long seed) {
        return new GameRuleCore(new DeterministicRandomSource(seed),
                GenerationPolicy.ordinaryPaidDefaults(), 9, 1);
    }

    private static void assertSeparatorLimit(String member) {
        for (char separator : new char[]{';', '/', ':', '.'}) {
            long count = member.chars().filter(value -> value == separator).count();
            assertTrue((double) count / member.length() <= 0.20,
                    () -> "separator " + separator + " exceeds 20% in " + member);
        }
    }
}
