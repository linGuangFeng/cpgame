package com.cpgame.crazy777.generator;

import com.cpgame.crazy777.generator.model.RoundCandidate;
import com.cpgame.crazy777.generator.model.RoundMode;
import com.cpgame.crazy777.generator.model.RoundResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class OrdinaryRewardEnumeratorTest {
    @Test void reelCatalogCoversAlternatingVisibleStructures() {
        assertEquals(71, OrdinaryRewardEnumerator.reelCatalogSize());
    }

    @Test void ordinaryRewardsFillRangeUpToCapWithoutScatter() {
        LoaderLimits limits = limits(1, 2500);
        List<OrdinaryRewardEnumerator.Board> boards = OrdinaryRewardEnumerator.enumerate(
                limits, 8, new SplittableRandom(57021));
        assertFalse(boards.isEmpty());
        Map<Integer, Integer> perRatio = new TreeMap<>();
        Set<String> unique = new HashSet<>();
        RoundFactory factory = new RoundFactory();
        RoundVerifier verifier = new RoundVerifier();
        boolean sawMix = false;
        boolean sawHigh = false;
        for (OrdinaryRewardEnumerator.Board board : boards) {
            assertTrue(unique.add(String.join(",", board.cells())));
            assertTrue(limits.accepts(false, board.ratio()));
            assertTrue(board.ratio() >= 1 && board.ratio() <= 2500);
            assertFalse(ResultUtil.isScatterTrigger(board.cells()));
            Map<String, String> wins = ResultUtil.evaluateRegularLines(board.cells());
            int ratio = wins.values().stream().mapToInt(GameRules.PAYTABLE::get).sum();
            assertEquals(ratio, board.ratio());
            perRatio.merge(board.ratio(), 1, Integer::sum);
            if (wins.containsValue("MIX")) sawMix = true;
            if (board.ratio() >= 50) sawHigh = true;
            RoundResult round = factory.create(new RoundCandidate(List.of(board.cells())),
                    1, new BigDecimal("0.5"), new BigDecimal("10000"));
            assertEquals(board.ratio() == 0 ? RoundMode.ORDINARY_LOSS : RoundMode.ORDINARY_WIN, verifier.verify(round).mode());
            assertEquals(board.ratio(), verifier.verify(round).totalMultiplier().intValueExact());
        }
        assertTrue(perRatio.values().stream().allMatch(n -> n <= 8));
        assertTrue(sawMix);
        assertTrue(sawHigh);
        assertTrue(boards.size() > 8);
        assertEquals(boards.size(), unique.size());
    }

    @Test void minAndMaxFilterOrdinaryBuckets() {
        List<OrdinaryRewardEnumerator.Board> onlyMix = OrdinaryRewardEnumerator.enumerate(
                limits(1, 1), 6, new SplittableRandom(57022));
        assertFalse(onlyMix.isEmpty());
        assertTrue(onlyMix.stream().allMatch(board -> board.ratio() == 1));
        assertTrue(onlyMix.size() <= 6);

        List<OrdinaryRewardEnumerator.Board> zeros = OrdinaryRewardEnumerator.enumerate(
                limits(0, 0), 4, new SplittableRandom(57023));
        assertEquals(4, zeros.size());
        assertTrue(zeros.stream().allMatch(board -> board.ratio() == 0));

        List<OrdinaryRewardEnumerator.Board> empty = OrdinaryRewardEnumerator.enumerate(
                limits(2499, 2499), 3, new SplittableRandom(57024));
        assertTrue(empty.isEmpty());
    }

    private static LoaderLimits limits(int min, int max) {
        Properties p = new Properties();
        p.setProperty("generation.normal-min-win-multiplier", Integer.toString(min));
        p.setProperty("generation.normal-max-win-multiplier", Integer.toString(max));
        p.setProperty("generation.special-min-win-multiplier", "25");
        p.setProperty("generation.special-max-win-multiplier", "2500");
        p.setProperty("generation.max-members-per-multiplier", "300");
        p.setProperty("generation.special-max-members-per-multiplier", "100");
        return new LoaderLimits(p);
    }
}
