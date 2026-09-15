package com.cpgame.replica.crazygems;

/** One result pool: PerKeyList_0 index and BetLog:0 lists, bucketed by final payout. */
public final class RedisKeys {
    private RedisKeys() { }

    public static String index(long gameId) {
        return "PerKeyList_0%08d".formatted(gameId);
    }

    static String legacySplitIndex(long gameId) {
        return "PerKeyList_1%08d".formatted(gameId);
    }

    public static String list(long gameId, int ratio) {
        return "BetLog:0%08d:%06d".formatted(gameId, ratio);
    }

    static String legacySplitList(long gameId, int ratio) {
        return "BetLog:1%08d:%06d".formatted(gameId, ratio);
    }

    // Only used to clean up the previous layout when clear-existing is enabled.
    static String legacyMaryIndex(long gameId) {
        return "MaryKeyList_%09d".formatted(gameId);
    }

    static String legacyMaryList(long gameId, int ratio) {
        return "MaryLog:%09d:%06d".formatted(gameId, ratio);
    }
}
