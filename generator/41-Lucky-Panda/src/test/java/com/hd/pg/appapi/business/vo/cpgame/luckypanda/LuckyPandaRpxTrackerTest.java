package com.hd.pg.appapi.business.vo.cpgame.luckypanda;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LuckyPandaRpxTrackerTest {
    @Test
    void paidStartWithOnePanBlockIsTimesTwo() {
        LuckyPandaRpxTracker tracker = new LuckyPandaRpxTracker();
        LuckyPandaBoard board = boardWithPanBlocks(1);
        assertEquals(2, tracker.next(board, 0));
    }

    @Test
    void paidStartWithoutPanStaysTimesOneWireZero() {
        LuckyPandaRpxTracker tracker = new LuckyPandaRpxTracker();
        LuckyPandaBoard board = boardWithPanBlocks(0);
        assertEquals(0, tracker.next(board, 0));
    }

    @Test
    void paidKeepsMaxOfTwoTimesPanBlocksAndNeverDrops() {
        LuckyPandaRpxTracker tracker = new LuckyPandaRpxTracker();
        assertEquals(2, tracker.next(boardWithPanBlocks(1), 0));
        assertEquals(2, tracker.next(boardWithPanBlocks(1), 0));
        assertEquals(6, tracker.next(boardWithPanBlocks(3), 0));
        assertEquals(6, tracker.next(boardWithPanBlocks(0), 0));
        assertEquals(6, tracker.next(boardWithPanBlocks(2), 0));
    }

    @Test
    void firstFreeFromZeroStartsAtTwoPlusTwoTimesPans() {
        LuckyPandaRpxTracker tracker = new LuckyPandaRpxTracker();
        tracker.next(boardWithPanBlocks(0), 0);
        assertEquals(2, tracker.next(boardWithPanBlocks(0), 1));
        assertEquals(4, tracker.next(boardWithPanBlocks(1), 2));
    }

    private static LuckyPandaBoard boardWithPanBlocks(int panBlocks) {
        List<List<LuckyPandaSymbol>> cells = new ArrayList<>();
        cells.add(fill(5, LuckyPandaSymbol.T));
        List<LuckyPandaSymbol> reel1 = new ArrayList<>();
        reel1.add(LuckyPandaSymbol.H1);
        int placed = 0;
        int pans = 0;
        while (placed < 5) {
            if (pans < panBlocks) {
                reel1.add(LuckyPandaSymbol.PAN);
                pans++;
                placed++;
                if (placed < 5 && pans < panBlocks) {
                    reel1.add(LuckyPandaSymbol.H5);
                    placed++;
                }
            } else {
                reel1.add(LuckyPandaSymbol.H5);
                placed++;
            }
        }
        cells.add(reel1);
        cells.add(fill(6, LuckyPandaSymbol.Q));
        cells.add(fill(6, LuckyPandaSymbol.J));
        cells.add(fill(6, LuckyPandaSymbol.K));
        cells.add(fill(5, LuckyPandaSymbol.A));
        return LuckyPandaBoard.fromCells(cells);
    }

    private static List<LuckyPandaSymbol> fill(int n, LuckyPandaSymbol symbol) {
        List<LuckyPandaSymbol> column = new ArrayList<>(n);
        for (int i = 0; i < n; i++) column.add(symbol);
        return column;
    }
}
