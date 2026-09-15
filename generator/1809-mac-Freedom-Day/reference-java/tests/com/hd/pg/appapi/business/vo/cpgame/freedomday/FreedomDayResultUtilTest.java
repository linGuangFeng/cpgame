package com.hd.pg.appapi.business.vo.cpgame.freedomday;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class FreedomDayResultUtilTest {
    @Test
    void boardToResultIsDeterministic() {
        int[] prop = noWinBoard();
        prop[7] = 6;
        prop[0] = 9; prop[5] = 9; prop[10] = 9;
        FreedomDayBoard board = new FreedomDayBoard(prop, new int[]{2, 3, 4, 5});
        FreedomDayEvaluation first = FreedomDayResultUtil.evaluate(board, new BigDecimal("0.01"), 1, 1);
        FreedomDayEvaluation second = FreedomDayResultUtil.evaluate(board, new BigDecimal("0.01"), 1, 1);
        assertEquals(first.getTotalMultiplier(), second.getTotalMultiplier());
        assertEquals(new BigDecimal("1"), first.getTotalMultiplier());
        assertEquals(new BigDecimal("0.01"), first.getTotalWin());
    }

    @Test
    void waysIncludeTheFourTopCells() {
        int[] prop = noWinBoard();
        prop[7] = 6;
        prop[0] = 9; prop[1] = 9;
        prop[5] = 9;
        prop[10] = 9; prop[11] = 9; prop[12] = 9;
        FreedomDayBoard board = new FreedomDayBoard(prop, new int[]{9, 2, 3, 4});
        FreedomDayEvaluation result = FreedomDayResultUtil.evaluate(board, BigDecimal.ONE, 1, 1);
        FreedomDayWin win = result.getWins().stream().filter(v -> v.getSymbol() == 9).findFirst().orElseThrow(AssertionError::new);
        assertEquals(12, win.getWays());
        assertEquals(Arrays.asList(0), win.getTopPositions());
    }

    @Test
    void normalWinningPageCollectsEveryBallAsPlusTwo() {
        int[] prop = noWinBoard();
        removeExtraSymbol(prop, 9, 3);
        prop[0] = 9; prop[5] = 9; prop[10] = 9;
        prop[2] = 1; prop[17] = 1; prop[29] = 1;
        FreedomDayEvaluation result = FreedomDayResultUtil.evaluate(
                new FreedomDayBoard(prop, new int[]{2, 3, 4, 5}), new BigDecimal("0.01"), 1, 2);
        FreedomDayWin win = result.getWins().stream()
                .filter(value -> value.getSymbol() == 9).findFirst().orElseThrow(AssertionError::new);
        assertEquals(6, result.getMultiplier());
        assertEquals(6, win.getMultiplier());
        assertEquals(new BigDecimal("0.06"), win.getWinMoney());
    }

    @Test
    void terminalPageDoesNotCollectVisibleBalls() {
        int[] prop = noWinBoard();
        prop[2] = 1; prop[17] = 1; prop[29] = 1;
        FreedomDayEvaluation result = FreedomDayResultUtil.evaluate(
                new FreedomDayBoard(prop, new int[]{2, 3, 4, 5}), BigDecimal.ONE, 4, 2);
        assertTrue(result.getWins().isEmpty());
        assertEquals(4, result.getMultiplier());
        assertEquals(0, result.getTotalWin().signum());
    }

    @Test
    void featureBoardContainsExactlyFourScatters() {
        FreedomDayBoard board = new FreedomDayBoardGenerator(new Random(2260)).generateFeatureTrigger();
        long count = Arrays.stream(board.getProp()).filter(v -> v == 12).count()
                + Arrays.stream(board.getTrl()).filter(v -> v == 12).count();
        assertEquals(4, count);
    }

    @Test
    void cascadeKeepsNonWinningSymbolsAndRefillsOnlyEmptyCells() {
        int[] prop = noWinBoard();
        prop[7] = 6;
        prop[0] = 9; prop[5] = 9; prop[10] = 9;
        FreedomDayBoard board = new FreedomDayBoard(prop, new int[]{9, 2, 3, 4});
        FreedomDayEvaluation evaluation = FreedomDayResultUtil.evaluate(board, BigDecimal.ONE, 1, 1);
        FreedomDayBoard next = new FreedomDayBoardGenerator(new Random(1)).cascade(board, evaluation, false);
        // 第一列只消除 index 0，所以原 index 1..4 下落后仍依次位于 index 1..4。
        assertArrayEquals(Arrays.copyOfRange(prop, 1, 5), Arrays.copyOfRange(next.getProp(), 1, 5));
        // 横排 index 0 消除后，原 1..3 左移到 0..2。
        assertArrayEquals(new int[]{2, 3, 4}, Arrays.copyOfRange(next.getTrl(), 0, 3));
    }

    @Test
    void featureBuyBuildsTriggerAndFreeSpinSequence() {
        FreedomDayRoundGenerator generator = new FreedomDayRoundGenerator(new FreedomDayBoardGenerator(new Random(2260)));
        JSONObject round = generator.generate(0.01, 1, true, 0);
        assertTrue(round.getIntValue("totalSpins") >= 11);
        assertEquals(3, round.getJSONArray("spins").getJSONObject(0).getIntValue("type"));
        assertTrue(round.getBigDecimal("mul").signum() >= 0);
    }

    @Test
    void independentLossNeverNeedsRedisAndAlwaysRecalculatesToZero() {
        FreedomDayRoundGenerator generator = new FreedomDayRoundGenerator();
        for (int i = 0; i < 100; i++) {
            JSONObject round = generator.generateIndependentLoss(0.01, 1);
            assertEquals(0, round.getBigDecimal("mul").signum());
            assertEquals(1, round.getIntValue("totalSpins"));
            assertTrue(round.getJSONArray("spins").getJSONObject(0)
                    .getJSONArray("props").getJSONObject(0).getJSONArray("win_arr").isEmpty());
        }
    }

    @Test
    void cachedCompactBoardsAreRecalculatedForCurrentBet() {
        int[] winning = noWinBoard();
        winning[7] = 6;
        winning[0] = 9; winning[5] = 9; winning[10] = 9;
        String compact = encode(winning, new int[]{2, 3, 4, 5})
                + encode(noWinBoard(), new int[]{2, 3, 4, 5});
        JSONObject round = new FreedomDayRoundGenerator().fromCachedCompact(compact, 0.02, 2, false);
        assertEquals(0, BigDecimal.ONE.compareTo(round.getBigDecimal("mul")));
        assertEquals(new BigDecimal("0.04"), round.getJSONArray("spins").getJSONObject(0)
                .getBigDecimal("spinWin"));
        // 2260 前端按 p[i][p[i].length - 1] 读取主盘中奖位置。
        // demo 的协议是二维路径数组，不能退化成 [0, 5, 10]。
        com.alibaba.fastjson.JSONArray positions = round.getJSONArray("spins").getJSONObject(0)
                .getJSONArray("props").getJSONObject(0)
                .getJSONArray("win_arr").getJSONObject(0).getJSONArray("p");
        assertEquals(3, positions.size());
        assertEquals(0, positions.getJSONArray(0).getIntValue(0));
        assertEquals(5, positions.getJSONArray(1).getIntValue(0));
        assertEquals(10, positions.getJSONArray(2).getIntValue(0));
    }

    @Test
    void fiveHundredRandomRoundsAreInternallyConsistent() {
        FreedomDayRoundGenerator generator = new FreedomDayRoundGenerator(new FreedomDayBoardGenerator(new Random(5002260)));
        for (int roundIndex = 0; roundIndex < 500; roundIndex++) {
            JSONObject round = generator.generate(0.01, 1, false, 1);
            BigDecimal sum = BigDecimal.ZERO;
            for (Object spinValue : round.getJSONArray("spins")) {
                JSONObject spin = (JSONObject) spinValue;
                sum = sum.add(spin.getBigDecimal("spinMultiplier"));
                for (Object pageValue : spin.getJSONArray("props")) {
                    JSONObject page = (JSONObject) pageValue;
                    assertEquals(30, page.getJSONArray("prop").size());
                    assertEquals(4, page.getJSONArray("trl").size());
                    BigDecimal pageWin = BigDecimal.ZERO;
                    for (Object winValue : page.getJSONArray("win_arr")) {
                        pageWin = pageWin.add(((JSONObject) winValue).getBigDecimal("wm"));
                    }
                    assertEquals(0, pageWin.compareTo(page.getBigDecimal("tw")));
                }
            }
            assertEquals(0, sum.compareTo(round.getBigDecimal("mul")));
        }
    }

    private int[] noWinBoard() {
        int[] prop = new int[30];
        for (int reel = 0; reel < 6; reel++) {
            for (int row = 0; row < 5; row++) prop[reel * 5 + row] = 2 + (reel + row * 3) % 10;
        }
        return prop;
    }

    private void removeExtraSymbol(int[] prop, int symbol, int reelCount) {
        for (int reel = 0; reel < reelCount; reel++) {
            for (int row = 0; row < 5; row++) {
                int index = reel * 5 + row;
                if (prop[index] == symbol) prop[index] = 8;
            }
        }
    }

    private String encode(int[] prop, int[] trl) {
        StringBuilder result = new StringBuilder(34);
        for (int symbol : prop) result.append(Character.toUpperCase(Character.forDigit(symbol, 14)));
        for (int symbol : trl) result.append(Character.toUpperCase(Character.forDigit(symbol, 14)));
        return result.toString();
    }
}
