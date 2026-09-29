package com.cpgame.luckywheel.core;

/** 押注档解锁：格子数、倍率、转盘。小常量表，不是抓包直方图。 */
public final class BetUnlock {
    private BetUnlock() {}

    public static int cells(int betLevel) {
        return betLevel < 5 ? 2 : 3;
    }

    public static int profile(int betLevel) {
        return betLevel < 5 ? 1 : 5;
    }

    public static int[] multipliers(int betLevel) {
        return betLevel >= 10 ? new int[]{2, 5, 10} : new int[]{2, 5};
    }

    public static boolean scatter(int betLevel) {
        return betLevel >= 5;
    }

    /** 档位5/10 低档转盘；50/100 升级转盘奖。 */
    public static int[] wheelAwards(int betLevel) {
        if (betLevel < 5) return new int[0];
        if (betLevel >= 50) return new int[]{25, 50, 75, 100, 150, 250, 500, 1000};
        return new int[]{25, 50, 75, 100, 150};
    }
}
