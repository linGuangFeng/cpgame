package com.cpgame.crazypiggy.generator;

import java.math.BigDecimal;
import java.util.Properties;

/** Immutable Redis output limits. All complete rounds share the ordinary range and cap. */
public final class LoaderLimits {
    private final BigDecimal normalMin, normalMax;
    public final int ordinaryCap;
    public LoaderLimits(Properties p) {
        normalMin = number(p, "0", "generation.normal-min-win-multiplier", "range.normal-min");
        normalMax = number(p, "999999999", "generation.normal-max-win-multiplier", "generation.normal-max-total-multiplier", "generation.normal-max-total-win-multiplier", "generation.normal-pool-max-win-multiplier", "range.normal-max");
        ordinaryCap = number(p, "300", "generation.max-members-per-multiplier").intValueExact();
        if (normalMin.signum()<0 || normalMax.compareTo(normalMin)<0 || ordinaryCap<1)
            throw new IllegalArgumentException("Invalid inclusive multiplier range or bucket retention");
    }
    public boolean accepts(boolean special, int value) { return accepts(special, BigDecimal.valueOf(value)); }
    public boolean accepts(boolean special, BigDecimal value) {
        return value.compareTo(normalMin)>=0 && value.compareTo(normalMax)<=0;
    }
    public int lossTarget(int configured) { return accepts(false, 0) ? configured : 0; }
    public static long attemptLimit(long target) { return Math.max(100_000L, Math.multiplyExact(Math.max(1, target), 10_000L)); }
    public static void checkAttempts(long attempts, long target) {
        if (attempts > attemptLimit(target)) throw new IllegalStateException("Configured range, weights or round limits cannot satisfy the requested count; candidate limit reached");
    }
    public static void checkKeys(Properties p) {
        java.util.Set<String> allowed=new java.util.HashSet<String>(java.util.Arrays.asList("generation.batch-size","generation.loss-count","generation.mary-max-win-multiplier","generation.mary-min-win-multiplier","generation.max-consecutive-wins","generation.max-members-per-multiplier","generation.mode.booster-wheel.weight","generation.mode.ordinary.weight","generation.normal-max-total-multiplier","generation.normal-max-total-win-multiplier","generation.normal-max-win-multiplier","generation.normal-min-win-multiplier","generation.normal-pool-max-win-multiplier","generation.special-count","generation.special-max-members-per-multiplier","generation.special-max-total-multiplier","generation.special-max-total-win-multiplier","generation.special-max-win-multiplier","generation.special-min-win-multiplier","generation.special-pool-max-win-multiplier","generation.win-count","range.normal-max","range.normal-min","range.special-max","range.special-min","redis.connect-timeout-ms","redis.database","redis.game-id","redis.host","redis.password","redis.port","redis.socket-timeout-ms","redis.ssl","redis.username","retention.special-per-multiplier"));
        for(String key:p.stringPropertyNames())if(!allowed.contains(key)&&!key.matches("generation\\.symbol\\.(HOT|SEV|H[2-7])\\.normal-weight")&&!key.matches("generation\\.symbol\\.H[2-7]\\.booster-weight")){if(key.toLowerCase(java.util.Locale.ROOT).contains("seed"))throw new IllegalArgumentException("正式配置禁止 seed: "+key);System.err.println("[warn] unused generator.properties key: "+key);};
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
