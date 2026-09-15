package com.cpgame.crazy777.generator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** protocol-r3-20260829 已确认、可直接实现的当前游戏规则常量。 */
public final class GameRules {
    public static final int GAME_ID = 57;
    public static final String GAME_NAME = "Crazy 777";
    public static final String PROTOCOL_REVISION = "protocol-r3-20260829";
    public static final String RULES_VERSION = "v1.5.10.250430";
    public static final String RULES_HASH = "sha256:852d3021084c1f4117203c4a6eda137747bf9c1f689b30decb9791e46bf66f2b";
    public static final int REEL_COUNT = 3;
    public static final int SLOTS_PER_REEL = 5;
    public static final int BOARD_SIZE = 15;
    public static final int FREE_SPIN_COUNT = 10;
    public static final int FULL_FREE_STEPS = 11;
    public static final int PAID_RPX = 1;
    public static final int FREE_RPX = 3;
    public static final String SCATTER_KEY = "5";
    public static final int SC_PER_REEL_MAX = 2;
    public static final int SC_VISIBLE_PER_REEL_MAX = 1;
    public static final int SC_BOARD_MAX = 4;
    public static final int WILD_PER_REEL_MAX = 2;
    public static final int WILD_BOARD_MAX = 3;
    public static final List<Integer> BET_LEVELS = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    public static final List<BigDecimal> BET_SIZES = List.of(
            new BigDecimal("0.5"), new BigDecimal("5"), new BigDecimal("50"));
    public static final List<String> REGULAR = List.of("H1", "H2", "H3", "H4", "H5", "H6");
    public static final Set<String> ALL_SYMBOLS = Set.of(
            "H1", "H2", "H3", "H4", "H5", "H6", "WILD", "SC", "BLANK");
    public static final Map<String, Integer> PAYTABLE = Map.of(
            "H1", 50, "H2", 25, "H3", 15, "H4", 6, "H5", 5,
            "H6", 3, "MIX", 1, "SC", 7, "WILD", 500);
    public static final int[][] PAYLINES = {
            {1, 1, 1}, {2, 2, 2}, {3, 3, 3}, {1, 2, 3}, {3, 2, 1}
    };
    public static final int[] VISIBLE_ROWS = {1, 2, 3};
    public static final List<String> IDLE_BOARD = List.of(
            "BLANK", "H4", "BLANK", "H6", "BLANK",
            "BLANK", "H3", "BLANK", "H6", "BLANK",
            "H4", "BLANK", "H2", "BLANK", "H5");

    private GameRules() {}

    public static BigDecimal betAmount(int bl, BigDecimal bs) {
        BigDecimal size = bs.stripTrailingZeros();
        if (!BET_LEVELS.contains(bl) || BET_SIZES.stream().noneMatch(v -> v.compareTo(size) == 0)) {
            throw new IllegalArgumentException("不支持的下注 bl=" + bl + ", bs=" + bs);
        }
        return size.multiply(BigDecimal.valueOf(bl));
    }
}
