package com.cpgame.g2110.core;

/** Platform Redis key contract for Bee Workshop gid 2110. */
public final class RedisKeys {
    public static final long GAME_ID = 8002110;

    private RedisKeys() {}

    public static void requireGame(String game) {
        if (!"8002110".equals(game)) throw new IllegalArgumentException("Bee Workshop keys require game 2110");
    }

    public static boolean special(GameRuleCore.RoundKind kind) {
        return kind == GameRuleCore.RoundKind.MYSTERY_BOX || kind == GameRuleCore.RoundKind.FREE_STICKY_SYMBOLS;
    }

    public static String normalIndex() { return String.format("PerKeyList_%09d", GAME_ID); }
    public static String maryIndex() { return String.format("MaryKeyList_%09d", GAME_ID); }
    public static String normalList(int multiplier) { return String.format("BetLog:0%08d:%06d", GAME_ID, multiplier); }
    public static String maryList(int multiplier) { return String.format("MaryLog:%09d:%06d", GAME_ID, multiplier); }
    public static String index(boolean special) { return special ? maryIndex() : normalIndex(); }
    public static String list(boolean special, int multiplier) { return special ? maryList(multiplier) : normalList(multiplier); }
}
