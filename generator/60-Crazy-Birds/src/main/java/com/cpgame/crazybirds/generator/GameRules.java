package com.cpgame.crazybirds.generator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 来自原站 config.spl、GameGlobalConfig 与真实 spin 的当前游戏规则。 */
public final class GameRules {
    public static final int GAME_ID = 60;
    public static final String GAME_NAME = "Crazy Birds";
    public static final String RULES_VERSION = "v1.5.10.250430";
    public static final String RULES_HASH = "sha256:60crazybirds-v1510250430-ways4096";
    public static final int REEL_COUNT = 6;
    public static final int ROWS = 4;
    public static final int BOARD_SIZE = 24;
    public static final int MIN_HIGH_REELS = 2;
    public static final int MIN_LOW_REELS = 3;
    public static final int SCATTER_TRIGGER_REELS = 3;
    public static final int BASE_FREE_SPINS = 8;
    public static final List<Integer> BET_LEVELS = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    public static final List<BigDecimal> BET_SIZES = List.of(
            new BigDecimal("1"), new BigDecimal("5"), new BigDecimal("50"));
    public static final List<Integer> AUTO = List.of(10, 30, 50, 100, 500);
    public static final List<String> LOW = List.of("9", "10", "J", "Q", "K", "A");
    public static final List<String> HIGH = List.of("S5", "S4", "S3", "S2", "S1");
    public static final Set<String> WILDS = Set.of("WILD", "WILDX2", "WILDX3", "WILDX5");
    public static final Map<String, Integer> WILD_RPX = Map.of(
            "WILD", 1, "WILDX2", 2, "WILDX3", 3, "WILDX5", 5);
    public static final Map<String, Map<Integer, BigDecimal>> PAYTABLE = Map.ofEntries(
            Map.entry("9", Map.of(3, bd("0.25"), 4, bd("0.5"), 5, bd("0.75"), 6, bd("1"))),
            Map.entry("10", Map.of(3, bd("0.25"), 4, bd("0.5"), 5, bd("0.75"), 6, bd("1"))),
            Map.entry("J", Map.of(3, bd("0.25"), 4, bd("0.75"), 5, bd("1"), 6, bd("1.5"))),
            Map.entry("Q", Map.of(3, bd("0.25"), 4, bd("0.75"), 5, bd("1"), 6, bd("1.5"))),
            Map.entry("K", Map.of(3, bd("0.5"), 4, bd("1"), 5, bd("1.5"), 6, bd("2"))),
            Map.entry("A", Map.of(3, bd("0.5"), 4, bd("1"), 5, bd("1.5"), 6, bd("2"))),
            Map.entry("S5", Map.of(2, bd("0.25"), 3, bd("1"), 4, bd("1.5"), 5, bd("2.25"), 6, bd("3.75"))),
            Map.entry("S4", Map.of(2, bd("0.25"), 3, bd("1"), 4, bd("1.5"), 5, bd("2.25"), 6, bd("3.75"))),
            Map.entry("S3", Map.of(2, bd("0.5"), 3, bd("1.5"), 4, bd("2.25"), 5, bd("3"), 6, bd("5"))),
            Map.entry("S2", Map.of(2, bd("0.5"), 3, bd("1.5"), 4, bd("2.25"), 5, bd("3"), 6, bd("5"))),
            Map.entry("S1", Map.of(2, bd("1"), 3, bd("2"), 4, bd("3"), 5, bd("5"), 6, bd("7.5")))
    );

    private GameRules() {}

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    public static BigDecimal betAmount(int bl, BigDecimal bs) {
        BigDecimal size = bs.stripTrailingZeros();
        if (!BET_LEVELS.contains(bl) || BET_SIZES.stream().noneMatch(v -> v.compareTo(size) == 0)) {
            throw new IllegalArgumentException("不支持的下注 bl=" + bl + ", bs=" + bs);
        }
        return size.multiply(BigDecimal.valueOf(bl));
    }

    public static int coord(int reel, int row) {
        return reel * 10 + row;
    }

    public static int indexOf(int reel, int row) {
        return reel * ROWS + row;
    }

    public static boolean isWild(String symbol) {
        return WILDS.contains(symbol);
    }

    public static boolean paysAs(String cell, String symbol) {
        return symbol.equals(cell) || isWild(cell);
    }

    public static int minReels(String symbol) {
        return HIGH.contains(symbol) ? MIN_HIGH_REELS : MIN_LOW_REELS;
    }
}
