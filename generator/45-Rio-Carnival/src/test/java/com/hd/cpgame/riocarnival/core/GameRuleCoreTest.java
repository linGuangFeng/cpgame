package com.hd.cpgame.riocarnival.core;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class GameRuleCoreTest {
    @Test void fixedPaylineAndWildPayoutComeFromCurrentRules() {
        java.util.List<String> board = Arrays.asList(
            "A","H3","H4", "Wild","H2","H5", "A","H3","H4", "A","H2","H5", "A","H3","H4");
        WinEvaluation e = ResultUtil.evaluate(board, new BigDecimal("0.02"), 1, 0);
        assertTrue(e.matches.containsKey("2"));
        assertEquals(new BigDecimal("6"), e.lineAwards.get(2));
        assertTrue(e.award.signum() > 0);
    }

    @Test void completeRoundsAreIndependentlyValidUniqueAndCodecRoundTrips() throws Exception {
        GameRuleCore core = new GameRuleCore(new SeededRoundRandom(450045L));
        RoundFactsCodec codec = new RoundFactsCodec();
        List<GeneratedRound> rounds = new ArrayList<GeneratedRound>();
        Set<String> members = new HashSet<String>();
        int losses = 0, wins = 0, free = 0, retriggers = 0;
        for (int i=0; i<2000; i++) {
            GeneratedRound round = core.generate(new BigDecimal("0.02"), 1);
            RoundResult result = RoundVerifier.verify(round);
            String member = codec.encode(round);
            GeneratedRound decoded = codec.decode(member);
            assertEquals(member, codec.encode(decoded));
            assertTrue(members.add(member));
            rounds.add(round);
            if ("ORDINARY_LOSS".equals(result.mode)) losses++;
            if ("ORDINARY_WIN".equals(result.mode)) wins++;
            if ("FREE_SPINS".equals(result.mode)) free++;
            retriggers += result.retriggerCount;
        }
        assertDoesNotThrow(() -> RoundVerifier.verifyUnique(rounds));
        assertTrue(losses > 0);
        assertTrue(wins > 0);
        assertTrue(free > 0);
        assertTrue(retriggers > 0);
    }

    @Test void verifierRejectsTamperedDerivedAward() {
        GeneratedRound round = new GameRuleCore(new SeededRoundRandom(45L))
            .generate(new BigDecimal("0.02"), 1);
        round.steps.get(0).wa = round.steps.get(0).wa.add(BigDecimal.ONE);
        assertThrows(IllegalArgumentException.class, () -> RoundVerifier.verify(round));
    }

    @Test void unsupportedBetIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new GameRuleCore(new SeededRoundRandom(1)).generate(new BigDecimal("0.03"),1));
    }
}
