package com.cpgame.fishinggo.core;

public final class RedisKeys {
    private RedisKeys() {}
    public static String normalIndex() { return "PerKeyList_008000054"; }
    public static String maryIndex() { return "MaryKeyList_008000054"; }
    public static String normalList(int multiplier) { return String.format("BetLog:008000054:%06d", multiplier); }
    public static String maryList(int multiplier) { return String.format("MaryLog:008000054:%06d", multiplier); }
    public static String index(ResultUtil.Outcome o) {
        return o == ResultUtil.Outcome.FREE_SPINS ? maryIndex() : normalIndex();
    }
    public static String list(ResultUtil.Outcome o, int multiplier) {
        return o == ResultUtil.Outcome.FREE_SPINS ? maryList(multiplier) : normalList(multiplier);
    }
}
