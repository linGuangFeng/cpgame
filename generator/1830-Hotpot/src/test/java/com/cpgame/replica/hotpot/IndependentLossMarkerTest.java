package com.cpgame.replica.hotpot;

import com.hd.pg.appapi.business.model.cpgame.hotpot.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IndependentLossMarkerTest {
    final CompleteRoundCodec codec = new CompleteRoundCodec();
    HotpotBoard board(int scatters) {
        int[] cells = new int[36];
        for (int i = 0; i < cells.length; i++) cells[i] = i % 10 + 1;
        for (int i = 0; i < scatters; i++) cells[i * 6] = 11;
        return new HotpotBoard(cells);
    }
    List<CompleteRoundFact.BoardFact> spin(HotpotBoard board) { return List.of(CompleteRoundFact.fromBoard(board)); }

    @Test void freeLossMarkersPreserveRetriggersAndCounts() {
        List<List<CompleteRoundFact.BoardFact>> spins = new ArrayList<>();
        spins.add(spin(board(3)));
        for (int i = 0; i < 15; i++) spins.add(spin(board(i == 4 ? 2 : 0)));
        var before = new CompleteRoundFact(CompleteRoundFact.VERSION, spins);
        String full = codec.encodeFull(before), compact = codec.encode(before);
        assertTrue(compact.length() < full.length());
        assertNotEquals("#", compact.split("\\|")[0]);
        assertNotEquals("#", compact.split("\\|")[5]);
        Set<CompleteRoundFact> distinct = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            var after = codec.decode(compact);
            assertEquals(codec.verify(before, 10, 30), codec.verify(after, 10, 30));
            assertEquals(before.spins().get(0), after.spins().get(0));
            assertEquals(before.spins().get(5), after.spins().get(5));
            for (int s = 1; s < after.spins().size(); s++) {
                if (s != 5) assertTrue(HotpotIndependentLossGenerator.isIndependentLoss(after.spins().get(s).get(0).toBoard(), HotpotSpinMode.FREE));
            }
            assertEquals(compact, codec.encode(after));
            distinct.add(after);
        }
        assertEquals(100, distinct.size());
    }

    @Test void freeCandidateNeverRetriggersAndPaidTwoScatterRemainsEligible() {
        assertTrue(HotpotIndependentLossGenerator.isIndependentLoss(board(2), HotpotSpinMode.PAID));
        assertFalse(HotpotIndependentLossGenerator.isIndependentLoss(board(2), HotpotSpinMode.FREE));
        var generator = new HotpotIndependentLossGenerator();
        Random random = new Random(18300914);
        for (int i = 0; i < 10000; i++)
            assertTrue(HotpotIndependentLossGenerator.isIndependentLoss(generator.generate(random, HotpotSpinMode.FREE), HotpotSpinMode.FREE));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("#1"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("0"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(codec.encodeFull(new CompleteRoundFact(CompleteRoundFact.VERSION, List.of(spin(board(0))))) + "#"));
    }

    @Test void naturalWinningCascadesKeepEveryPageIncludingZeroTail() {
        var factory = new CompleteRoundFactory(); Random random = new Random(183012);
        int cascades = 0, checked = 0;
        for (int i = 0; i < 300; i++) {
            CompleteRoundFactory.GeneratedRound generated;
            try { generated = factory.generate(random, 10, 30); }
            catch (CompleteRoundFactory.RoundRejectedException rejected) { continue; }
            var before = generated.fact(); var after = codec.decode(codec.encode(before));
            assertEquals(codec.verify(before, 10, 30), codec.verify(after, 10, 30));
            for (int s = 0; s < before.spins().size(); s++) if (before.spins().get(s).size() > 1) {
                assertEquals(before.spins().get(s), after.spins().get(s)); cascades++;
            }
            checked++;
        }
        assertTrue(checked > 100); assertTrue(cascades > 5);
    }
}
