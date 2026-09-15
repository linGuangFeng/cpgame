package com.cpgame.crazybirds.generator;

import com.cpgame.crazybirds.generator.model.WinWay;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 独立开奖复核。牌面为 6×4 reel-major；坐标为 reel*10+row。
 * 赔付 = spl[symbol][reelCount] * bet * ways * unique-pxl 乘积。
 */
public final class ResultUtil {
    private ResultUtil() {}

    public static void validateBoard(List<String> board) {
        if (board == null || board.size() != GameRules.BOARD_SIZE) {
            throw new IllegalArgumentException("rskl 必须恰好包含 24 项");
        }
        int sc = 0;
        int wild = 0;
        for (String symbol : board) {
            if (symbol == null) throw new IllegalArgumentException("空符号");
            if ("SC".equals(symbol)) sc++;
            if (GameRules.isWild(symbol)) wild++;
            if (!GameRules.PAYTABLE.containsKey(symbol) && !"SC".equals(symbol) && !GameRules.isWild(symbol)) {
                throw new IllegalArgumentException("未知符号: " + symbol);
            }
        }
        if (sc > 6) throw new IllegalArgumentException("整盘 SC 超过抓包上限");
        if (wild > 8) throw new IllegalArgumentException("整盘 WILD 超过抓包上限");
    }

    public static int scatterReels(List<String> board) {
        validateBoard(board);
        int reels = 0;
        for (int reel = 0; reel < GameRules.REEL_COUNT; reel++) {
            boolean hit = false;
            for (int row = 0; row < GameRules.ROWS; row++) {
                if ("SC".equals(board.get(GameRules.indexOf(reel, row)))) {
                    hit = true;
                    break;
                }
            }
            if (hit) reels++;
        }
        return reels;
    }

    public static boolean isScatterTrigger(List<String> board) {
        return scatterReels(board) >= GameRules.SCATTER_TRIGGER_REELS;
    }

    public static Map<Integer, Integer> pxlFromBoard(List<String> board) {
        Map<Integer, Integer> pxl = new LinkedHashMap<>();
        for (int reel = 0; reel < GameRules.REEL_COUNT; reel++) {
            for (int row = 0; row < GameRules.ROWS; row++) {
                String cell = board.get(GameRules.indexOf(reel, row));
                Integer rpx = GameRules.WILD_RPX.get(cell);
                if (rpx != null && rpx > 1) pxl.put(GameRules.coord(reel, row), rpx);
            }
        }
        return pxl;
    }

    public static List<WinWay> evaluateWays(List<String> board, BigDecimal bet) {
        validateBoard(board);
        List<WinWay> wins = new ArrayList<>();
        List<String> order = new ArrayList<>();
        order.addAll(GameRules.LOW);
        order.addAll(List.of("S5", "S4", "S3", "S2", "S1"));
        for (String symbol : order) {
            WinWay way = wayFor(board, symbol, bet);
            if (way != null) wins.add(way);
        }
        return wins;
    }

    public static BigDecimal payout(List<WinWay> wins) {
        BigDecimal total = BigDecimal.ZERO;
        for (WinWay way : wins) total = total.add(way.amount());
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    public static List<List<List<Integer>>> wmklOf(List<WinWay> wins) {
        List<List<List<Integer>>> out = new ArrayList<>();
        for (WinWay way : wins) out.add(way.groups());
        return out;
    }

    public static List<String> wsklOf(List<WinWay> wins) {
        List<String> out = new ArrayList<>();
        for (WinWay way : wins) out.add(way.symbol());
        return out;
    }

    private static WinWay wayFor(List<String> board, String symbol, BigDecimal bet) {
        List<List<Integer>> groups = new ArrayList<>();
        for (int reel = 0; reel < GameRules.REEL_COUNT; reel++) {
            List<Integer> cells = new ArrayList<>();
            for (int row = 0; row < GameRules.ROWS; row++) {
                String cell = board.get(GameRules.indexOf(reel, row));
                if (GameRules.paysAs(cell, symbol)) cells.add(GameRules.coord(reel, row));
            }
            if (cells.isEmpty()) break;
            groups.add(cells);
        }
        if (groups.size() < GameRules.minReels(symbol)) return null;
        Map<Integer, BigDecimal> spl = GameRules.PAYTABLE.get(symbol);
        BigDecimal unit = spl.get(groups.size());
        if (unit == null) return null;
        int ways = 1;
        for (List<Integer> g : groups) ways *= g.size();
        BigDecimal wild = BigDecimal.ONE;
        Map<Integer, Integer> pxl = pxlFromBoard(board);
        for (List<Integer> g : groups) {
            for (Integer coord : g) {
                Integer rpx = pxl.get(coord);
                if (rpx != null && rpx > 1) wild = wild.multiply(BigDecimal.valueOf(rpx));
            }
        }
        BigDecimal amount = unit.multiply(bet).multiply(BigDecimal.valueOf(ways)).multiply(wild)
                .setScale(2, RoundingMode.HALF_UP);
        return new WinWay(symbol, groups, ways, wild.intValue(), amount);
    }
}
