package com.cpgame.crazypiggy.generator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 当前游戏已验收、可直接实现的规则常量。 */
public final class GameRules {
    public static final int GAME_ID = 56;
    public static final String GAME_NAME = "Crazy Piggy";
    public static final String RULES_VERSION = "v1.5.10.250430";
    public static final String RULES_HASH = "7edd2945da54923875932aa9feb1bfd657c9c67cca832637060f8419573480ab";
    public static final List<String> SYMBOLS = List.of("HOT", "SEV", "H2", "H3", "H4", "H5", "H6", "H7");
    public static final Map<String, Integer> PAYTABLE = Map.of(
            "HOT", 200, "SEV", 50, "H2", 20, "H3", 10,
            "H4", 8, "H5", 5, "H6", 3, "H7", 1);
    public static final List<BigDecimal> BET_SIZES = List.of(
            new BigDecimal("0.5"), new BigDecimal("5"), new BigDecimal("50"));
    public static final List<Integer> BET_LEVELS = List.of(1,2,3,4,5,6,7,8,9,10);
    public static final int[][] PAYLINES = {
            {0, 3, 6}, {1, 4, 7}, {2, 5, 8}, {0, 4, 8}, {2, 4, 6}
    };

    private GameRules() {}
}
