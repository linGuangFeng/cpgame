package com.cpgame.replica.luckypanda;

import java.util.Locale;

/** Platform consumer keys adapted from 1809/42, using redis.game-id 41. */
public final class RedisKeyContract {
    private RedisKeyContract() { }

    public static String normalIndex(long gameId) {
        return String.format(Locale.ROOT, "PerKeyList_%09d", gameId);
    }

    public static String specialIndex(long gameId) {
        return String.format(Locale.ROOT, "MaryKeyList_%09d", gameId);
    }

    public static String normalList(long gameId, int multiplier) {
        return String.format(Locale.ROOT, "BetLog:0%08d:%06d", gameId, multiplier);
    }

    public static String specialList(long gameId, int multiplier) {
        return String.format(Locale.ROOT, "MaryLog:%09d:%06d", gameId, multiplier);
    }
}
