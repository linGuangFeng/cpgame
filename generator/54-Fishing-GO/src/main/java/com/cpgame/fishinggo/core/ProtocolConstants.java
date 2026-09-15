package com.cpgame.fishinggo.core;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ProtocolConstants {
    public static final int GAME_ID = 54;
    public static final String DIRECTORY = "54-Fishing-GO";
    public static final String GAME_NAME = "Fishing GO";
    public static final String RULES_VERSION = "fishing-go-rules-23e7a379b8a38d25";
    public static final String RULES_HASH = "23e7a379b8a38d25d638cd0726cab97c49a6c425c88e2dd371c82007f9f81463";
    public static final BigDecimal MIN_BET_SIZE = new BigDecimal("0.02");
    public static final int MIN_BET_LEVEL = 1;
    public static final BigDecimal MIN_TOTAL_BET = new BigDecimal("0.40");
    public static final int REELS = 5;
    public static final int ROWS = 3;
    public static final int CELLS = 15;
    public static final int FREE_SPINS = 12;
    public static final int MAX_WILD_PER_REEL = 2;
    public static final int MAX_WILD_PAID = 5;
    public static final int MAX_WILD_FREE = 3;
    public static final int MAX_SCATTER_PER_REEL = 2;
    public static final int MAX_SCATTER_PAID = 7;
    public static final int MAX_SCATTER_FREE_REEL = 1;
    public static final int MAX_SCATTER_FREE_BOARD = 4;
    public static final List<String> ORDINARY = List.of("A", "J", "K", "Q", "S1", "S2", "S3", "S4");
    public static final List<String> ORDER = List.of("A", "J", "K", "Q", "S1", "S2", "S3", "S4", "SC", "WILD");
    public static final Set<String> ALL = Set.copyOf(ORDER);
    public static final Map<String, Map<Integer, Integer>> PAYTABLE = Map.of(
            "A", Map.of(3, 6, 4, 15, 5, 50),
            "J", Map.of(3, 5, 4, 10, 5, 40),
            "K", Map.of(3, 6, 4, 15, 5, 50),
            "Q", Map.of(3, 5, 4, 10, 5, 40),
            "S1", Map.of(3, 30, 4, 60, 5, 150),
            "S2", Map.of(3, 20, 4, 50, 5, 120),
            "S3", Map.of(3, 15, 4, 45, 5, 100),
            "S4", Map.of(3, 10, 4, 45, 5, 80));
    private ProtocolConstants() {}
}
