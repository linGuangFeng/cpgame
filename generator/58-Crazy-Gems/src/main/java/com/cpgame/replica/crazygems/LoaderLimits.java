package com.cpgame.replica.crazygems;

import java.math.BigDecimal;
import java.util.Properties;

/** Immutable Redis output limits. Zero is subject to the same inclusive range. */
public final class LoaderLimits {
    private final BigDecimal normalMin, normalMax, specialMin, specialMax;
    public final int specialCap;
    public LoaderLimits(Properties p) {
        normalMin = number(p, "0", "generation.normal-min-win-multiplier", "range.normal-min", "generation.min-win-multiplier");
        specialMin = number(p, "0", "generation.special-min-win-multiplier", "generation.mary-min-win-multiplier", "range.special-min", "generation.min-win-multiplier");
        normalMax = number(p, "999999999", "generation.normal-max-win-multiplier", "generation.normal-max-total-multiplier", "generation.normal-max-total-win-multiplier", "generation.normal-pool-max-win-multiplier", "range.normal-max", "generation.max-win-multiplier");
        specialMax = number(p, "999999999", "generation.special-max-win-multiplier", "generation.mary-max-win-multiplier", "generation.special-max-total-multiplier", "generation.special-max-total-win-multiplier", "generation.special-pool-max-win-multiplier", "range.special-max", "generation.max-win-multiplier");
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
        for(String key:p.stringPropertyNames()) if(key.startsWith("range.") || key.startsWith("retention.") || (key.startsWith("generation.normal-")&&!key.equals("generation.normal-count")) || (key.startsWith("generation.special-")&&!key.equals("generation.special-count")) || key.startsWith("generation.mary-")) throw new IllegalArgumentException("Unsupported split-pool configuration: "+key+"; use generation.min-win-multiplier / max-win-multiplier / max-members-per-multiplier for the shared pool");
        java.util.Set<String> allowed=new java.util.HashSet<String>(java.util.Arrays.asList("generation.total-members","generation.batch-size","generation.clear-existing","generation.mary-max-win-multiplier","generation.mary-min-win-multiplier","generation.max-members-per-multiplier","generation.max-win-multiplier","generation.min-win-multiplier","generation.normal-count","generation.normal-max-total-multiplier","generation.normal-max-win-multiplier","generation.normal-min-win-multiplier","generation.normal-pool-max-win-multiplier","generation.rpx.1.weight","generation.rpx.10.weight","generation.rpx.15.weight","generation.rpx.2.weight","generation.rpx.3.weight","generation.rpx.5.weight","generation.special-count","generation.special-max-members-per-multiplier","generation.special-max-total-multiplier","generation.special-max-win-multiplier","generation.special-min-win-multiplier","generation.special-pool-max-win-multiplier","generation.symbol.H1.normal-weight","generation.symbol.H2.normal-weight","generation.symbol.H3.normal-weight","generation.symbol.H4.normal-weight","generation.symbol.H5.normal-weight","generation.symbol.H6.normal-weight","generation.symbol.H7.normal-weight","generation.symbol.WILD.normal-weight","range.normal-max","range.normal-min","range.special-max","range.special-min","redis.connect-timeout-ms","redis.database","redis.game-id","redis.host","redis.password","redis.port","redis.socket-timeout-ms","redis.ssl","redis.username","retention.special-per-multiplier"));
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
