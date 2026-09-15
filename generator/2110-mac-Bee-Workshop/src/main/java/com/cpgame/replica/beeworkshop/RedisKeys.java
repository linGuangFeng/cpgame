package com.cpgame.replica.beeworkshop;

/** Platform Redis key contract: PerKeyList / MaryKeyList / BetLog / MaryLog. */
public final class RedisKeys {
    public static final long GAME_ID = 2110;

    private RedisKeys() {}

    public static void requireGame(long game) {
        if (game <= 0) throw new IllegalArgumentException("redis.game-id must be positive");
    }

    public static boolean special(GameRuleCore.RoundKind kind) { return GameRuleCore.special(kind); }

    public static String normalIndex(long id) { return String.format("PerKeyList_%09d", id); }
    public static String maryIndex(long id) { return String.format("MaryKeyList_%09d", id); }
    public static String normalList(long id, int multiplier) { return String.format("BetLog:0%08d:%06d", id, multiplier); }
    public static String maryList(long id, int multiplier) { return String.format("MaryLog:%09d:%06d", id, multiplier); }
    public static String index(long id, boolean special) { return special ? maryIndex(id) : normalIndex(id); }
    public static String list(long id, boolean special, int multiplier) { return special ? maryList(id, multiplier) : normalList(id, multiplier); }
}
