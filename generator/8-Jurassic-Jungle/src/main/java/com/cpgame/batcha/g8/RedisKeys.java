package com.cpgame.batcha.g8;

/**
 * Local replica Redis key contract. Origin consumer protocol is UNKNOWN.
 * PerKeyList holds LOSS and ordinary WIN; MaryKeyList holds DRAGON complete Rounds.
 * Ratio is integer paytable-unit sum (payout/bet*10).
 */
public final class RedisKeys {
    public static final long GAME_ID = 8000008;

    private RedisKeys() { }

    public static boolean special(RoundMode mode) { return mode == RoundMode.DRAGON; }

    public static String normalIndex() { return String.format("PerKeyList_%09d", GAME_ID); }
    public static String maryIndex() { return String.format("MaryKeyList_%09d", GAME_ID); }
    public static String normalList(int ratio) { return String.format("BetLog:0%08d:%06d", GAME_ID, ratio); }
    public static String maryList(int ratio) { return String.format("MaryLog:%09d:%06d", GAME_ID, ratio); }

    public static String index(boolean special) { return special ? maryIndex() : normalIndex(); }
    public static String list(boolean special, int ratio) { return special ? maryList(ratio) : normalList(ratio); }

    public static String list(RoundMode mode, int ratio) { return list(special(mode), ratio); }
}
