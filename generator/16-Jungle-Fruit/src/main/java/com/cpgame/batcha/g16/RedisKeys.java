package com.cpgame.batcha.g16;

import java.math.BigDecimal;

/**
 * Platform Redis key contract for Jungle Fruit gid 16.
 * PerKeyList_%09d / MaryKeyList_%09d indexes; BetLog:0%08d:%06d / MaryLog:%09d:%06d lists.
 */
public final class RedisKeys {
    public static final long GAME_ID = 8000016;

    private RedisKeys() { }

    public static boolean special(RoundMode mode) {
        return mode == RoundMode.MARY || mode == RoundMode.FREE;
    }

    public static String normalIndex() { return String.format("PerKeyList_%09d", GAME_ID); }
    public static String maryIndex() { return String.format("MaryKeyList_%09d", GAME_ID); }
    public static String normalList(int ratio) { return String.format("BetLog:0%08d:%06d", GAME_ID, ratio); }
    public static String maryList(int ratio) { return String.format("MaryLog:%09d:%06d", GAME_ID, ratio); }

    public static String index(boolean special) { return special ? maryIndex() : normalIndex(); }
    public static String list(boolean special, int ratio) { return special ? maryList(ratio) : normalList(ratio); }

    public static String list(RoundMode mode, BigDecimal multiplier) {
        return list(special(mode), multiplier.intValueExact());
    }
}
