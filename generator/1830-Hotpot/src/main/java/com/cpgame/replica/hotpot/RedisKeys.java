package com.cpgame.replica.hotpot;

/** Platform Redis key contract. %09d / %08d use redis.game-id; %06d is the integer multiplier. */
public final class RedisKeys {
    private RedisKeys() { }

    public static String normalIndex(long id) { return String.format("PerKeyList_%09d", id); }
    public static String maryIndex(long id) { return String.format("MaryKeyList_%09d", id); }
    public static String normalList(long id, int ratio) { return String.format("BetLog:0%08d:%06d", id, ratio); }
    public static String maryList(long id, int ratio) { return String.format("MaryLog:%09d:%06d", id, ratio); }
}
