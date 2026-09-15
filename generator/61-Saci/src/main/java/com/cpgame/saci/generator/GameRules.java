package com.cpgame.saci.generator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 61 Saci 已由赔表、前端与 3368 个抓包 Step 印证的规则常量。 */
public final class GameRules {
    public static final int GAME_ID = 61;
    public static final String GAME_NAME = "Saci";
    public static final String DIRECTORY_NAME = "61-Saci";
    public static final String ACTIVE_REVISION = "v1.5.10.250821";
    public static final String RULES_VERSION = "v1.5.10.250821-rules-saci-rebuild";
    public static final String RULES_HASH = "saci-v2-656af5aa-rebuild-20260910";
    public static final String API_NAMESPACE = "d-saci";
    public static final int REELS = 5;
    public static final int ROWS = 3;
    public static final int BOARD_SIZE = 15;
    public static final int BASE_BET_FACTOR = 20;
    public static final int WILD_ENERGY_CAP = 6;
    public static final int VORTEX_COUNT = 3;
    public static final int FREE_BASE_SPINS = 10;
    public static final int PAID_SCATTER_PER_REEL_MAX = 1;
    public static final int PAID_SCATTER_BOARD_MAX = 3;
    public static final int PAID_WILD_PER_REEL_MAX = 2;
    public static final int PAID_WILD_BOARD_MAX = 2;
    public static final int PAID_SPLIT_BOARD_MAX = 2;
    public static final List<Integer> BET_LEVELS = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    public static final List<BigDecimal> BET_SIZES = List.of(
            new BigDecimal("0.02"), new BigDecimal("0.1"), new BigDecimal("0.5"));
    public static final List<Integer> AUTO = List.of(10, 30, 50, 100, 500);
    public static final List<Integer> BUY = List.of(30, 60, 90, 65);
    public static final Set<String> ORDINARY = Set.of("1", "2", "3", "4", "5", "6", "7", "8");
    public static final String WILD = "9";
    public static final String SCATTER = "a";
    public static final Map<String, Map<Integer, Integer>> PAYTABLE = Map.of(
            "1", Map.of(3, 2, 4, 3, 5, 5),
            "2", Map.of(3, 3, 4, 4, 5, 6),
            "3", Map.of(3, 4, 4, 5, 5, 7),
            "4", Map.of(3, 5, 4, 6, 5, 8),
            "5", Map.of(3, 10, 4, 12, 5, 15),
            "6", Map.of(3, 12, 4, 15, 5, 20),
            "7", Map.of(3, 15, 4, 20, 5, 30),
            "8", Map.of(3, 20, 4, 30, 5, 50),
            "9", Map.of(5, 100));
    public static final List<String> IDLE_BOARD = List.of(
            "181", "131", "171",
            "151", "141", "161",
            "121", "111", "181",
            "171", "151", "131",
            "141", "161", "121");

    private GameRules() {}

    public static BigDecimal betAmount(int bl, BigDecimal bs) {
        BigDecimal size = bs.stripTrailingZeros();
        if (!BET_LEVELS.contains(bl) || BET_SIZES.stream().noneMatch(v -> v.compareTo(size) == 0)) {
            throw new IllegalArgumentException("不支持的下注 bl=" + bl + ", bs=" + bs);
        }
        return size.multiply(BigDecimal.valueOf(bl)).multiply(BigDecimal.valueOf(BASE_BET_FACTOR));
    }

    public static String energyKey(BigDecimal paidBet) {
        return paidBet.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString();
    }

    public static int coord(int reel, int row) {
        return reel * 10 + row;
    }

    public static int reelOf(int coord) {
        return coord / 10;
    }

    public static int rowOf(int coord) {
        return coord % 10;
    }

    public static int indexOf(int reel, int row) {
        return reel * ROWS + row;
    }
}
