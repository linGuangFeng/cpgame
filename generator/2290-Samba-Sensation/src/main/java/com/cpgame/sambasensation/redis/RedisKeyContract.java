package com.cpgame.sambasensation.redis;

import java.util.Locale;

/** 平台通用索引/列表合同；仅结构来自成熟消费者，ID与member为当前游戏自己的。 */
public final class RedisKeyContract {
    private RedisKeyContract() { }
    public static String normalIndex(long gameId) { return String.format(Locale.ROOT, "PerKeyList_%09d", gameId); }
    public static String specialIndex(long gameId) { return String.format(Locale.ROOT, "MaryKeyList_%09d", gameId); }
    public static String normalList(long gameId, int multiplier) { return String.format(Locale.ROOT, "BetLog:0%08d:%06d", gameId, multiplier); }
    public static String normalIndex(long gameId, int betType) {
        checkFloor(betType);
        return String.format(Locale.ROOT, "PerKeyList_%d%08d", betType - 1, gameId);
    }
    public static String normalList(long gameId, int multiplier, int betType) {
        checkFloor(betType);
        return String.format(Locale.ROOT, "BetLog:%d%08d:%06d", betType - 1, gameId, multiplier);
    }
    private static void checkFloor(int betType) {
        if (betType < 1 || betType > 3) throw new IllegalArgumentException("betType must be 1..3");
    }
    public static String specialList(long gameId, int multiplier) { return String.format(Locale.ROOT, "MaryLog:%09d:%06d", gameId, multiplier); }
}
