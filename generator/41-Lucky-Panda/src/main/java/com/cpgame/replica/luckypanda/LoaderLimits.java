package com.cpgame.replica.luckypanda;

import java.math.BigDecimal;
import java.util.Properties;

/** Immutable Redis output limits. Zero is subject to the same inclusive range. */
public final class LoaderLimits {
    private final BigDecimal normalMin, normalMax, specialMin, specialMax;
    public final int specialCap;
    public LoaderLimits(Properties p) {
        normalMin = number(p, "0", "generation.normal-min-win-multiplier", "range.normal-min");
        specialMin = number(p, "0", "generation.special-min-win-multiplier", "generation.mary-min-win-multiplier", "range.special-min");
        normalMax = number(p, "999999999", "generation.normal-max-win-multiplier", "generation.normal-max-total-multiplier", "generation.normal-max-total-win-multiplier", "generation.normal-pool-max-win-multiplier", "range.normal-max");
        specialMax = number(p, "999999999", "generation.special-max-win-multiplier", "generation.mary-max-win-multiplier", "generation.special-max-total-multiplier", "generation.special-max-total-win-multiplier", "generation.special-pool-max-win-multiplier", "range.special-max");
        specialCap = number(p, "100", "generation.special-max-members-per-multiplier", "retention.special-per-multiplier").intValueExact();
        if (normalMin.signum()<0 || specialMin.signum()<0 || normalMax.compareTo(normalMin)<0 || specialMax.compareTo(specialMin)<0 || specialCap<1)
            throw new IllegalArgumentException("Invalid inclusive multiplier range or special bucket retention");
    }
    public boolean accepts(boolean special, int value) { return accepts(special, BigDecimal.valueOf(value)); }
    public boolean accepts(boolean special, BigDecimal value) {
        return value.compareTo(special ? specialMin : normalMin)>=0 && value.compareTo(special ? specialMax : normalMax)<=0;
    }
    public int lossTarget(int configured) { return accepts(false, 0) ? configured : 0; }
    public static long attemptLimit(long target) { return Math.max(100_000L, Math.multiplyExact(Math.max(1, target), 10_000L)); }
    public static void checkAttempts(long attempts, long target) {
        if (attempts > attemptLimit(target)) throw new IllegalStateException("Configured range, weights or round limits cannot satisfy the requested count; candidate limit reached");
    }
    public static void checkKeys(Properties p) {
        java.util.Set<String> allowed=new java.util.HashSet<String>(java.util.Arrays.asList("generation.batch-size","generation.bet-level","generation.bet-size","generation.mary-max-win-multiplier","generation.mary-min-win-multiplier","generation.max-consecutive-wins","generation.max-mary-spins","generation.max-members-per-multiplier","generation.normal-count","generation.normal-max-win-multiplier","generation.normal-min-win-multiplier","generation.special-count","generation.special-max-members-per-multiplier","generation.special-max-win-multiplier","generation.special-min-win-multiplier","generation.symbol.A.cascade-refill-weight","generation.symbol.A.free-cascade-refill-weight","generation.symbol.A.free-start-weight","generation.symbol.A.paid-start-weight","generation.symbol.H1.cascade-refill-weight","generation.symbol.H1.free-cascade-refill-weight","generation.symbol.H1.free-start-weight","generation.symbol.H1.paid-start-weight","generation.symbol.H2.cascade-refill-weight","generation.symbol.H2.free-cascade-refill-weight","generation.symbol.H2.free-start-weight","generation.symbol.H2.paid-start-weight","generation.symbol.H3.cascade-refill-weight","generation.symbol.H3.free-cascade-refill-weight","generation.symbol.H3.free-start-weight","generation.symbol.H3.paid-start-weight","generation.symbol.H4.cascade-refill-weight","generation.symbol.H4.free-cascade-refill-weight","generation.symbol.H4.free-start-weight","generation.symbol.H4.paid-start-weight","generation.symbol.H5.cascade-refill-weight","generation.symbol.H5.free-cascade-refill-weight","generation.symbol.H5.free-start-weight","generation.symbol.H5.paid-start-weight","generation.symbol.J.cascade-refill-weight","generation.symbol.J.free-cascade-refill-weight","generation.symbol.J.free-start-weight","generation.symbol.J.paid-start-weight","generation.symbol.K.cascade-refill-weight","generation.symbol.K.free-cascade-refill-weight","generation.symbol.K.free-start-weight","generation.symbol.K.paid-start-weight","generation.symbol.Pan.cascade-refill-weight","generation.symbol.Pan.free-cascade-refill-weight","generation.symbol.Pan.free-start-weight","generation.symbol.Pan.paid-start-weight","generation.symbol.Q.cascade-refill-weight","generation.symbol.Q.free-cascade-refill-weight","generation.symbol.Q.free-start-weight","generation.symbol.Q.paid-start-weight","generation.symbol.Scat.cascade-refill-weight","generation.symbol.Scat.free-cascade-refill-weight","generation.symbol.Scat.free-start-weight","generation.symbol.Scat.paid-start-weight","generation.symbol.T.cascade-refill-weight","generation.symbol.T.free-cascade-refill-weight","generation.symbol.T.free-start-weight","generation.symbol.T.paid-start-weight","generation.symbol.Wild.cascade-refill-weight","generation.symbol.Wild.free-cascade-refill-weight","generation.symbol.Wild.free-start-weight","generation.symbol.Wild.paid-start-weight","redis.connect-timeout-ms","redis.database","redis.game-id","redis.host","redis.password","redis.port","redis.socket-timeout-ms","redis.ssl","redis.username"));
        for(String key:p.stringPropertyNames())if(!allowed.contains(key))throw new IllegalArgumentException("未知或未支持的配置项: "+key);
        for(String key:new String[]{"redis.ssl","redis.clear-game-prefix","generation.clear-existing"})if(p.containsKey(key)&&!p.getProperty(key).trim().equalsIgnoreCase("true")&&!p.getProperty(key).trim().equalsIgnoreCase("false"))throw new IllegalArgumentException(key+" must be true or false");
        new LoaderLimits(p);
    }
    private static BigDecimal number(Properties p, String fallback, String... keys) {
        BigDecimal result = null;
        for (String key : keys) if (p.containsKey(key)) {
            BigDecimal value;
            try { value = new BigDecimal(p.getProperty(key).trim()); }
            catch (RuntimeException e) { throw new IllegalArgumentException("Invalid numeric configuration: " + key, e); }
            if (result != null && result.compareTo(value)!=0) throw new IllegalArgumentException("Conflicting configuration aliases: " + String.join(", ", keys));
            result = value;
        }
        return result == null ? new BigDecimal(fallback) : result;
    }
}
