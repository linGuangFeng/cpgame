package com.cpgame.junglekings;

import java.math.BigDecimal;
import java.util.List;

/**
 * Platform Redis key contract.
 * Underscore/BetLog first digit is the paid play variant:
 * 0 = one reel (top or bottom), 1 = both reels (double stake + x2).
 */
public final class RedisKeys {
    public static final long GAME_ID = 8000002;
    public static final int SINGLE_LINE = 0;
    public static final int BOTH_LINES = 1;

    private RedisKeys() { }

    public static int betType(List<String> chessboards) {
        if (chessboards == null || chessboards.isEmpty()) {
            throw new IllegalArgumentException("chessboards required");
        }
        return chessboards.size() >= 2 ? BOTH_LINES : SINGLE_LINE;
    }

    public static String index(int betType) {
        requireBetType(betType);
        return String.format("PerKeyList_%d%08d", betType, GAME_ID);
    }

    public static String list(int betType, int ratio) {
        requireBetType(betType);
        if (ratio < 0) throw new IllegalArgumentException("ratio must be non-negative");
        return String.format("BetLog:%d%08d:%06d", betType, GAME_ID, ratio);
    }

    public static String list(List<String> chessboards, BigDecimal multiplier) {
        return list(betType(chessboards), multiplier.intValueExact());
    }

    public static String maryIndex(int betType) {
        requireBetType(betType);
        return String.format("MaryKeyList_%d%08d", betType, GAME_ID);
    }

    public static String maryList(int betType, int ratio) {
        requireBetType(betType);
        return String.format("MaryLog:%d%08d:%06d", betType, GAME_ID, ratio);
    }

    private static void requireBetType(int betType) {
        if (betType != SINGLE_LINE && betType != BOTH_LINES) {
            throw new IllegalArgumentException("betType must be 0 (one reel) or 1 (both reels)");
        }
    }
}
