package com.cpgame.crazybirds.generator;

import com.cpgame.crazybirds.generator.model.WinWay;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ResultUtilTest {
    @Test
    void ordinaryWin122MatchesCapturedSpin() {
        List<String> board = List.of(
                "S2", "S2", "S5", "S5",
                "9", "WILD", "S2", "S2",
                "S2", "10", "9", "WILD",
                "S3", "S1", "S2", "A",
                "J", "S2", "WILD", "S3",
                "Q", "S2", "S1", "S4");
        List<WinWay> wins = ResultUtil.evaluateWays(board, BigDecimal.ONE);
        assertEquals(new BigDecimal("122.00"), ResultUtil.payout(wins));
        assertEquals(List.of("S5", "S2"), ResultUtil.wsklOf(wins));
    }

    @Test
    void wildx5FreeSpin95MatchesCapturedSpin() {
        List<String> board = List.of(
                "S2", "S2", "S3", "S3",
                "Q", "S3", "S5", "WILDX5",
                "9", "J", "K", "S3",
                "S5", "K", "S3", "S3",
                "J", "10", "10", "S2",
                "S3", "S1", "K", "K");
        List<WinWay> wins = ResultUtil.evaluateWays(board, BigDecimal.ONE);
        assertEquals(new BigDecimal("95.00"), ResultUtil.payout(wins));
    }

    @Test
    void threeScatterReelsTriggerFree() {
        List<String> board = List.of(
                "S3", "SC", "S1", "J",
                "K", "S5", "S5", "SC",
                "S3", "SC", "S3", "A",
                "10", "S1", "K", "Q",
                "S1", "10", "S4", "A",
                "A", "A", "Q", "J");
        assertTrue(ResultUtil.isScatterTrigger(board));
        assertEquals(3, ResultUtil.scatterReels(board));
    }
}
