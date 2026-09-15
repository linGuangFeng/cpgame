package com.hd.cpgame.magicscroll2.core;

import java.math.BigDecimal;

public final class GameConstants {
    public static final String GAME_ID = "2001007";
    public static final String DIRECTORY_NAME = "2001007-Magic-Scroll-2";
    public static final String GAME_NAME = "Magic Scroll 2";
    public static final String RULES_HASH = "sha256:ab8f86b20f45615af560b928c2f5bd860ea6abf641d461d2e200a3e4aaed3fcd";
    public static final String RULES_VERSION = "1.2.6";
    public static final String ROUND_SCHEMA_VERSION = "r2";
    public static final String REDIS_MEMBER_PREFIX = "MS2A1";
    /** Captured GameInfo idle board; display only, never a spin result source. */
    public static final String IDLE_FORMATION =
            "9,6,7,99,99,199,6,8,12,99,199,199,11,12,9,199,199,102,5,12,11,99,199,199,12,6,8,99,199,199,6,4,12,99,99,199";
    public static final BigDecimal RTP_PERCENT = new BigDecimal("96.04");
    public static final int COLUMNS = 6;
    public static final int ENCODED_ROWS = 6;
    public static final int CELL_COUNT = COLUMNS * ENCODED_ROWS;
    public static final int INITIAL_ACTIVE_ROWS = 3;
    public static final int MAX_ACTIVE_ROWS = 6;
    public static final int MAX_WAYS = 46656;
    public static final BigDecimal MINIMUM_TOTAL_BET = new BigDecimal("0.40");
    public static final BigDecimal BASE_BET_DIVISOR = new BigDecimal("20");
    public static final int WILD = 0;
    public static final int BONUS = 1;
    public static final int XSPLIT = 2;
    public static final int EMPTY = 99;
    public static final int[][] PAYTABLE = new int[13][7];

    static {
        set(3, 15, 40, 80, 150);
        set(4, 10, 15, 40, 75);
        set(5, 9, 12, 30, 50);
        set(6, 8, 11, 25, 40);
        set(7, 7, 10, 20, 35);
        set(8, 6, 8, 16, 30);
        set(9, 5, 7, 14, 25);
        set(10, 4, 6, 12, 22);
        set(11, 4, 6, 12, 21);
        set(12, 4, 6, 12, 20);
    }

    private static void set(int symbol, int c3, int c4, int c5, int c6) {
        PAYTABLE[symbol][3] = c3;
        PAYTABLE[symbol][4] = c4;
        PAYTABLE[symbol][5] = c5;
        PAYTABLE[symbol][6] = c6;
    }

    private GameConstants() {}
}
