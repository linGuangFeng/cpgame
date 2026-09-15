package com.cpgame.replica.luckypanda;

import com.hd.pg.appapi.business.vo.cpgame.luckypanda.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class IndependentLossMarkerTest {
    final CompleteRoundCodec codec = new CompleteRoundCodec();
    final Random random = new Random(410914);

    LuckyPandaBoard loss(int pans) { return LuckyPandaIndependentLossGenerator.generateWithPanCount(random, pans); }
    CompleteRoundFact.PageFact page(LuckyPandaBoard b, LuckyPandaRpxTracker tracker, int spin) {
        return new CompleteRoundFact.PageFact(b, tracker.next(b, spin), List.of(), List.of());
    }
    LuckyPandaBoard trigger() {
        List<String> tokens = new ArrayList<>(loss(0).toRskl());
        for (int i : new int[]{0, 5, 11, 17}) tokens.set(i, "1Scat");
        return LuckyPandaBoard.fromRskl(tokens);
    }
    CompleteRoundFact freeRound(boolean retrigger) {
        LuckyPandaRpxTracker tracker = new LuckyPandaRpxTracker();
        var paid = List.of(page(trigger(), tracker, 0));
        List<List<CompleteRoundFact.PageFact>> spins = new ArrayList<>();
        int count = retrigger ? 20 : 10;
        for (int i = 1; i <= count; i++) {
            if (i == count) {
                LuckyPandaBoard win = LuckyPandaBoard.fromRskl(Collections.nCopies(34, "1T"));
                spins.add(List.of(page(win, tracker, i), page(loss(0), tracker, i)));
            } else spins.add(List.of(page(retrigger && i == 4 ? trigger() : loss((i - 1) % 3), tracker, i)));
        }
        return new CompleteRoundFact(BigDecimal.ONE, 1, paid, spins);
    }

    @Test void preservesPanCountsMultiplierTrajectoryAwardsAndCascadeTail() {
        for (boolean retrigger : new boolean[]{false, true}) {
            CompleteRoundFact before = freeRound(retrigger);
            String full = codec.encodeFull(before), compact = codec.encode(before);
            assertTrue(compact.contains("|#0|#1|#2"));
            assertTrue(compact.length() < full.length());
            for (int repeat = 0; repeat < 30; repeat++) {
                CompleteRoundFact after = codec.decode(compact);
                assertEquals(codec.verify(before, 10), codec.verify(after, 10));
                assertEquals(before.paid().get(0).board().toRskl(), after.paid().get(0).board().toRskl());
                assertEquals(2, after.freeSpins().get(0).get(0).rpx());
                assertEquals(4, after.freeSpins().get(1).get(0).rpx());
                assertEquals(8, after.freeSpins().get(2).get(0).rpx());
                for (int i = 0; i < before.freeSpins().size(); i++) {
                    var a = before.freeSpins().get(i); var b = after.freeSpins().get(i);
                    assertEquals(a.size(), b.size());
                    for (int p = 0; p < a.size(); p++) {
                        assertEquals(a.get(p).rpx(), b.get(p).rpx());
                        assertEquals(a.get(p).board().tokens(LuckyPandaSymbol.PAN), b.get(p).board().tokens(LuckyPandaSymbol.PAN));
                        if (a.size() > 1 || a.get(p).board().scatterTokens() >= 4)
                            assertEquals(a.get(p).board().toRskl(), b.get(p).board().toRskl());
                    }
                }
                assertEquals(compact, codec.encode(after));
            }
        }
    }

    @Test void everySupportedPanCountIsReallyZeroAndFresh() {
        Set<List<String>> boards = new HashSet<>();
        for (int pans = 0; pans <= LuckyPandaIndependentLossGenerator.MAX_MARKER_PANS; pans++) {
            for (int i = 0; i < 200; i++) {
                var board = loss(pans);
                assertEquals(pans, board.tokens(LuckyPandaSymbol.PAN));
                assertFalse(LuckyPandaResultUtil.evaluate(board, BigDecimal.ONE, 1, 10).hasWaysWin());
                assertEquals(0, board.scatterTokens());
                boards.add(board.toRskl());
            }
        }
        assertTrue(boards.size() > 5500);
    }

    @Test void inconsistentLegacyMultiplierIsPreservedAndMalformedMarkersRejected() {
        var board = loss(1);
        var fact = new CompleteRoundFact(BigDecimal.ONE, 1,
                List.of(new CompleteRoundFact.PageFact(board, 17, List.of(), List.of())), List.of());
        assertFalse(codec.encode(fact).startsWith("#"));
        assertEquals(board.toRskl(), codec.decode(codec.encode(fact)).paid().get(0).board().toRskl());
        assertEquals(17, codec.decode(codec.encode(fact)).paid().get(0).rpx());
        for (String token : List.of("#", "#-1", "#30", "#01", "#999999", "#1;#2", "0"))
            assertThrows(IllegalArgumentException.class, () -> codec.decode("lp1|bs=1|bl=1|P=" + token));
    }

    @Test void concurrentDecodesDoNotShareMultiplierState() throws Exception {
        String encoded = codec.encode(freeRound(false));
        var expected = codec.verify(encoded, 10);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < 8; t++) tasks.add(() -> {
                for (int i = 0; i < 50; i++) assertEquals(expected, codec.verify(encoded, 10));
                return null;
            });
            for (Future<Void> future : pool.invokeAll(tasks)) future.get();
        } finally { pool.shutdownNow(); }
    }
}
