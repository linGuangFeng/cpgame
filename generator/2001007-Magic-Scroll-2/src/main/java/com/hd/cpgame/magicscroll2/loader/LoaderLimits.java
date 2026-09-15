package com.hd.cpgame.magicscroll2.loader;

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
        java.util.Set<String> allowed=new java.util.HashSet<String>(java.util.Arrays.asList("generation.batch-size","generation.loss-count","generation.loss.constructive-attempts","generation.loss.fallback-samples","generation.mary-max-win-multiplier","generation.mary-min-win-multiplier","generation.material.dirt-weight","generation.material.empty-weight","generation.max-consecutive-wins","generation.max-members-per-multiplier","generation.max-round-steps","generation.normal-max-total-multiplier","generation.normal-max-total-win-multiplier","generation.normal-max-win-multiplier","generation.normal-min-win-multiplier","generation.normal-pool-max-win-multiplier","generation.special-count","generation.special-max-members-per-multiplier","generation.special-max-total-multiplier","generation.special-max-total-win-multiplier","generation.special-max-win-multiplier","generation.special-min-win-multiplier","generation.special-pool-max-win-multiplier","generation.symbol.10-weight","generation.symbol.11-weight","generation.symbol.12-weight","generation.symbol.3-weight","generation.symbol.4-weight","generation.symbol.5-weight","generation.symbol.6-weight","generation.symbol.7-weight","generation.symbol.8-weight","generation.symbol.9-weight","generation.win-count","paid.bet","range.normal-max","range.normal-min","range.special-max","range.special-min","redis.connect-timeout-ms","redis.database","redis.enabled","redis.game-id","redis.host","redis.password","redis.port","redis.socket-timeout-ms","redis.ssl","redis.username","retention.special-per-multiplier"));
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
