package com.cpgame.luckywheel.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 43 派奖是拼出来的定额，不是押注×倍数。
 * 通用公式 {@code cs * bl * mul} 要对齐定额，必须 {@code cs = 1/bl}，押注金额恒为 1，{@code mul} 就是定额。
 */
public final class LuckyWheelStake {
    public static final int MAX_ODD = 1000;
    private static final int SCALE = 8;

    private LuckyWheelStake() { }

    public static BigDecimal cs(int betLevel) {
        if (betLevel <= 0) throw new IllegalArgumentException("betLevel");
        return BigDecimal.ONE.divide(BigDecimal.valueOf(betLevel), SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal ba(int betLevel) {
        return cs(betLevel).multiply(BigDecimal.valueOf(betLevel)).stripTrailingZeros();
    }
}
