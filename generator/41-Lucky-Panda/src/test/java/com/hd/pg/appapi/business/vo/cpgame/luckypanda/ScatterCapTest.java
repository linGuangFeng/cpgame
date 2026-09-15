package com.hd.pg.appapi.business.vo.cpgame.luckypanda;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScatterCapTest {
    @Test
    void capturedMaximaAreThreeBlocksPerReelAndFiveTotal() {
        assertEquals(3, GameRuleCore.SCAT_COLUMN_MAX_BLOCKS);
        assertEquals(5, GameRuleCore.SCAT_TOTAL_MAX_BLOCKS);
        assertEquals(4, GameRuleCore.SCAT_COLUMN_MAX_CELLS);
        assertEquals(9, GameRuleCore.SCAT_TOTAL_MAX_CELLS);
    }

    @Test
    void fixtureLossBoardIsInsideCaps() {
        LuckyPandaBoard board = LuckyPandaBoard.fromRskl(List.of(
                "1H5", "1H4", "1J", "1H3", "1Q",
                "1T", "2H5", "2H3", "1H1",
                "1H2", "4H2", "1H2",
                "1H1", "1A", "1H4", "1H3", "1J", "1H2",
                "1H2", "2K", "3T",
                "1Q", "1H3", "1Scat", "1H1", "1J"));
        assertTrue(GameRuleCore.withinScatterCaps(board));
        assertEquals(1, board.scatterTokens());
        assertEquals(1, board.scatterTokensOnReel(5));
    }

    @Test
    void twoScatterBlocksOnTheSameColumnAreLegal() {
        LuckyPandaBoard board = LuckyPandaBoard.fromRskl(List.of(
                "1Scat", "1H1", "1Scat", "1H2", "1H3",
                "1H4", "1H5", "1A", "1K", "1Q", "1J",
                "1T", "1H1", "1H2", "1H3", "1H4", "1H5",
                "1A", "1K", "1Q", "1J", "1T", "1H1",
                "1H2", "1H3", "1H4", "1H5", "1A", "1K",
                "1Q", "1J", "1T", "1H1", "1H2"));
        assertEquals(2, board.scatterTokensOnReel(0));
        assertTrue(GameRuleCore.withinScatterCaps(board));
        assertTrue(GameRuleCore.withinCapturedCaps(board));
    }

    @Test
    void fourScatterBlocksOnOneOuterReelExceedsColumnCap() {
        LuckyPandaBoard board = LuckyPandaBoard.fromRskl(List.of(
                "1Scat", "1Scat", "1Scat", "1Scat", "1H1",
                "1H2", "1H3", "1H4", "1H5", "1A", "1K",
                "1Q", "1J", "1T", "1H1", "1H2", "1H3",
                "1H4", "1H5", "1A", "1K", "1Q", "1J",
                "1T", "1H1", "1H2", "1H3", "1H4", "1H5",
                "1A", "1K", "1Q", "1J", "1T"));
        assertEquals(4, board.scatterTokensOnReel(0));
        assertFalse(GameRuleCore.withinScatterCaps(board));
    }

    @Test
    void sixScatterBlocksExceedsTotalCapEvenIfSpread() {
        LuckyPandaBoard board = LuckyPandaBoard.fromRskl(List.of(
                "1Scat", "1H1", "1H2", "1H3", "1H4",
                "1Scat", "1H5", "1A", "1K", "1Q", "1J",
                "1Scat", "1T", "1H1", "1H2", "1H3", "1H4",
                "1Scat", "1H5", "1A", "1K", "1Q", "1J",
                "1Scat", "1T", "1H1", "1H2", "1H3", "1H4",
                "1Scat", "1H5", "1A", "1K", "1Q"));
        assertEquals(6, board.scatterTokens());
        assertFalse(GameRuleCore.withinScatterCaps(board));
    }
}
