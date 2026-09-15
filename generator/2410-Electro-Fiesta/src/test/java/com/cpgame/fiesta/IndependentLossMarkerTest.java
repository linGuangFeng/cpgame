package com.cpgame.fiesta;

import org.junit.jupiter.api.Test;
import java.security.SecureRandom;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IndependentLossMarkerTest {
    CandidateFactory factory() {
        Properties p = new Properties();
        for (int symbol : GameRuleCore.SYMBOLS) {
            p.setProperty("generation.symbol.initial." + symbol, "1");
            p.setProperty("generation.symbol.respin-reel." + symbol, "1");
        }
        for (int m : new int[]{2,3,5,10}) p.setProperty("generation.material.multiplier." + m, "1");
        return new CandidateFactory(new SecureRandom(), new GameRuleCore(), p);
    }

    @Test void ordinaryLossMarkersNeverTriggerFeaturesAndRegenerate() {
        RoundCodec codec = new RoundCodec(); ResultUtil util = new ResultUtil(new GameRuleCore());
        CandidateFactory factory = factory(); Set<String> boards = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            assertEquals("EF1:#", codec.encode(factory.ordinary(false)));
            GameRound after = codec.decode("EF1:#");
            assertEquals(RoundOutcome.ORDINARY_LOSS, util.analyze(after).outcome());
            assertEquals(0, util.analyze(after).integerMultiplier());
            assertFalse(CandidateFactory.structuralFeature(after.states().get(0).board()));
            assertEquals("EF1:#", codec.encode(after));
            assertEquals(codec.encodeFull(after), codec.encodeFull(codec.decode(codec.encodeFull(after))));
            boards.add(codec.encodeFull(after));
        }
        assertTrue(boards.size() > 1900);
    }

    @Test void lockedColumnsAndStickyMultipliersKeepTheirExactStates() {
        RoundCodec codec = new RoundCodec(); ResultUtil util = new ResultUtil(new GameRuleCore());
        CandidateFactory factory = factory();
        for (int i = 0; i < 100; i++) {
            for (GameRound before : List.of(factory.respinRound(11), factory.multiplierRound(11), factory.ordinary(true))) {
                String full = codec.encodeFull(before);
                assertEquals(full, codec.encode(before));
                GameRound after = codec.decode(full);
                assertEquals(util.analyze(before), util.analyze(after));
                assertEquals(full, codec.encodeFull(after));
            }
        }
        for (String bad : List.of("#", "0", "EF1:#1", "EF1:#.", "EF1:#.#", "EF1:"))
            assertThrows(IllegalArgumentException.class, () -> codec.decode(bad));
    }

    @Test void zeroPayoutWithTwoMatchingFullReelsIsNotIndependent() {
        var state = new RoundState(RoundState.Mode.NORMAL, new int[]{2,2,2,3,3,3,2,2,2},
                new int[9], new int[0], 0, -1, 0);
        var round = new GameRound(List.of(state));
        assertEquals(0, new GameRuleCore().payoutUnits(state.board()));
        assertNotEquals("EF1:#", new RoundCodec().encode(round));
    }
}
