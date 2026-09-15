package com.cpgame.luckywheel.loader;

import java.util.Locale;

/**
 * 两套奖池各自再分解锁档。下划线后第一位：0 未解锁（bet&lt;5），1 特殊1（bet&gt;=5 解锁新玩法）。
 * 普通奖走 PerKeyList/BetLog；玛丽奖走 MaryKeyList/MaryLog。
 */
public final class RedisKeyContract {
    public static final int UNLOCK_LOCKED = 0;
    public static final int UNLOCK_SPECIAL_1 = 1;

    private RedisKeyContract() { }

    public static int unlockDigit(int betProfile) {
        if (betProfile == 1) return UNLOCK_LOCKED;
        if (betProfile == 5) return UNLOCK_SPECIAL_1;
        throw new IllegalArgumentException("未知下注档案: " + betProfile);
    }

    public static String normalIndex(long gameId, int betProfile) {
        return String.format(Locale.ROOT, "PerKeyList_%d%08d", unlockDigit(betProfile), gameId);
    }

    public static String specialIndex(long gameId, int betProfile) {
        return String.format(Locale.ROOT, "MaryKeyList_%d%08d", unlockDigit(betProfile), gameId);
    }

    public static String normalList(long gameId, int betProfile, int multiplier) {
        return String.format(Locale.ROOT, "BetLog:%d%08d:%06d", unlockDigit(betProfile), gameId, multiplier);
    }

    public static String specialList(long gameId, int betProfile, int multiplier) {
        return String.format(Locale.ROOT, "MaryLog:%d%08d:%06d", unlockDigit(betProfile), gameId, multiplier);
    }
}
