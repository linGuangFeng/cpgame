package com.cpgame.luckycatii;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Canonical Lucky Cat II constants from rules-core-canonical.json and capture caps. */
public final class GameRules {
    public static final int GAME_ID = 50;
    public static final String GAME_NAME = "Lucky Cat II";
    public static final String DIRECTORY_NAME = "50-Lucky-Cat-II";
    public static final String RULES_VERSION = "v1.5.10.250430";
    public static final String RULES_HASH = "4fcb4da38b457ee89e699b66ae9e258af2c73a622fc324202f20b7d7957c95f6";
    public static final List<String> SYMBOLS = List.of("WILD", "S1", "S2", "S3", "S4", "S5", "S6");
    public static final Map<String, Integer> PAYTABLE = Map.of(
            "WILD", 80, "S1", 25, "S2", 20, "S3", 15, "S4", 7, "S5", 5, "S6", 2);
    public static final List<BigDecimal> BET_SIZES = List.of(
            new BigDecimal("0.1"), BigDecimal.ONE, BigDecimal.TEN);
    public static final List<Integer> BET_LEVELS = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    public static final int PAYLINE_COUNT = 5;
    /** Reel-major row coordinates for lines 1..5. */
    public static final int[][] PAYLINE_ROWS = {
            {0, 0, 0}, {1, 1, 1}, {2, 2, 2}, {0, 1, 2}, {2, 1, 0}
    };
    public static final Set<Integer> CONFIRMED_WHEEL_MULTIPLIERS = Set.of(2, 3, 4, 5, 10);
    public static final int WHEEL_RULE_MAXIMUM = 10;
    public static final int MAX_WILD_PER_REEL = 3;
    public static final int MAX_WILD_PER_BOARD = 9;
    public static final int MAX_LUCKY_RESPINS = 1;

    private GameRules() {}

    public static int cellIndex(int reel, int row) {
        return reel * 3 + row;
    }

    public static boolean legalBet(BigDecimal betSize, int betLevel) {
        if (betSize == null || !BET_LEVELS.contains(betLevel)) return false;
        return BET_SIZES.stream().anyMatch(v -> v.compareTo(betSize) == 0);
    }

    public static BigDecimal betAmount(BigDecimal betSize, int betLevel) {
        return betSize.multiply(BigDecimal.valueOf((long) betLevel * PAYLINE_COUNT)).stripTrailingZeros();
    }
}
