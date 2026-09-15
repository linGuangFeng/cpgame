package com.hd.pg.appapi.business.vo.cpgame.crazygems;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic payline result. Amounts are derived from the board plus bet; never stored in Redis. */
public final class CrazyGemsEvaluation {
    private final Map<String, String> wmkl;
    private final BigDecimal paySum;
    private final int rpx;
    private final int multiplierDeci;

    public CrazyGemsEvaluation(Map<String, String> wmkl, BigDecimal paySum, int rpx, int multiplierDeci) {
        this.wmkl = Collections.unmodifiableMap(new LinkedHashMap<>(wmkl));
        this.paySum = paySum;
        this.rpx = rpx;
        this.multiplierDeci = multiplierDeci;
    }

    public Map<String, String> wmkl() { return wmkl; }
    public BigDecimal paySum() { return paySum; }
    public int rpx() { return rpx; }
    /** Integer stake-multiplier × 10, so 0.4x → 4. Redis key uses this. */
    public int multiplierDeci() { return multiplierDeci; }
    public boolean win() { return !wmkl.isEmpty(); }
    public boolean loss() { return wmkl.isEmpty(); }
    public boolean minecartSpecial() { return rpx > 1; }

    public BigDecimal winAmount(BigDecimal betSize, int betLevel) {
        return paySum.multiply(BigDecimal.valueOf(rpx)).multiply(betSize).multiply(BigDecimal.valueOf(betLevel));
    }
}
