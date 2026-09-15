package com.cpgame.replica.edmmania;

import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaBoard;
import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaBoardGenerator;
import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaEvaluation;
import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaResultUtil;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class EdmManiaGridAndContinuityTest {
    @Test void generatedBoardsCarryOriginalMergedGridsAndFrames() {
        EdmManiaBoardGenerator generator = new EdmManiaBoardGenerator(new Random(20102010L));
        int withGrids = 0;
        int withFrames = 0;
        for (int i = 0; i < 200; i++) {
            EdmManiaBoard board = generator.generate(false);
            if (!board.getGrids().isEmpty()) withGrids++;
            if (!board.getGoldFrames().isEmpty() || !board.getSilverFrames().isEmpty()) withFrames++;
            for (List<Integer> group : board.getGrids()) {
                assertTrue(group.size() >= 2 && group.size() <= 4);
                int reel = group.get(0) / 5;
                assertTrue(reel >= 1 && reel <= 4);
            }
        }
        assertTrue(withGrids >= 180, "original EDM Mania almost always shows stacked symbols, grids=" + withGrids);
        assertTrue(withFrames >= 120, "silver/gold frames are visible on real boards, frames=" + withFrames);
    }

    @Test void legalMergedGridRoundTripsAndCountsAsOneVisibleSymbol() {
        int[] prop = baseBoard();
        prop[5] = 9;
        prop[6] = 9;
        EdmManiaBoard board = new EdmManiaBoard(prop, new int[]{2, 3, 4, 5},
                List.of(List.of(5, 6)), List.of(List.of(5, 6)), List.of());
        EdmManiaEvaluation evaluation = EdmManiaResultUtil.evaluate(board, BigDecimal.ONE, 1, 2);
        assertEquals(1, board.positionsOnReel(1).stream().filter(p -> p.getIndices().equals(List.of(5, 6))).count());

        CompleteRoundFact fact = new CompleteRoundFact(1, false,
                List.of(List.of(new CompleteRoundFact.BoardFact(toList(prop), List.of(2, 3, 4, 5),
                        board.getGrids(), board.getGoldFrames(), board.getSilverFrames()))));
        String encoded = new CompleteRoundCodec().encode(fact);
        assertFalse(encoded.startsWith("{") || encoded.startsWith("["), "Redis member must stay compact ASCII");
        assertEquals(68, encoded.length());
        assertEquals(0, encoded.length() % 34);
        CompleteRoundFact decoded = new CompleteRoundCodec().decode(encoded);
        assertEquals(List.of(List.of(5, 6)), decoded.spins().get(0).get(0).grids());
        assertEquals(List.of(List.of(5, 6)), decoded.spins().get(0).get(0).gf());
        assertNotNull(evaluation);
    }

    @Test void rejectsCrossReelMismatchOverlapAndOrphanFrame() {
        int[] crossReel = baseBoard();
        crossReel[4] = crossReel[5] = 7;
        assertThrows(IllegalArgumentException.class, () -> new EdmManiaBoard(crossReel, new int[]{2,3,4,5},
                List.of(List.of(4, 5)), List.of(), List.of()));
        int[] mismatch = baseBoard(); mismatch[5] = 7; mismatch[6] = 8;
        assertThrows(IllegalArgumentException.class, () -> new EdmManiaBoard(mismatch, new int[]{2,3,4,5},
                List.of(List.of(5, 6)), List.of(), List.of()));
        int[] plain = baseBoard(); plain[5] = plain[6] = 7;
        assertThrows(IllegalArgumentException.class, () -> new EdmManiaBoard(plain, new int[]{2,3,4,5},
                List.of(), List.of(List.of(5, 6)), List.of()));
    }

    @Test void rejectsUnexplainedSymbolChangeBetweenAdjacentCascadePages() {
        CompleteRoundFactory factory = new CompleteRoundFactory();
        CompleteRoundFact original = null;
        Random random = new Random(2010201001L);
        for (int i = 0; i < 20_000 && original == null; i++) {
            try {
                CompleteRoundFact candidate = factory.generate(random, false, 10).fact();
                if (candidate.spins().get(0).size() > 1) original = candidate;
            } catch (CompleteRoundFactory.RoundRejectedException ignored) { }
        }
        assertNotNull(original, "seeded stream must produce a cascading round");
        List<List<CompleteRoundFact.BoardFact>> spins = new ArrayList<>();
        for (List<CompleteRoundFact.BoardFact> spin : original.spins()) spins.add(new ArrayList<>(spin));
        List<CompleteRoundFact.BoardFact> paid = spins.get(0);
        CompleteRoundFact.BoardFact next = paid.get(1);
        List<Integer> changed = new ArrayList<>(next.prop());
        // At least one bottom survivor exists in naturally generated pages; changing all bottom cells guarantees one illegal mutation.
        for (int reel = 0; reel < 6; reel++) changed.set(reel * 5 + 4, changed.get(reel * 5 + 4) == 11 ? 10 : 11);
        paid.set(1, new CompleteRoundFact.BoardFact(changed, next.trl(), next.grids(), next.gf(), next.sl()));
        CompleteRoundFact mutated = new CompleteRoundFact(1, original.featureBuy(),
                spins.stream().map(List::copyOf).toList());
        CompleteRoundCodec codec = new CompleteRoundCodec();
        assertThrows(IllegalArgumentException.class,
                () -> codec.verify(codec.encode(mutated), 10, false));
    }

    private static int[] baseBoard() {
        int[] prop = new int[30];
        for (int reel = 0; reel < 6; reel++) for (int row = 0; row < 5; row++)
            prop[reel * 5 + row] = 2 + (reel * 2 + row * 3) % 10;
        return prop;
    }

    private static List<Integer> toList(int[] values) {
        List<Integer> result = new ArrayList<>();
        for (int value : values) result.add(value);
        return result;
    }
}
