package com.cpgame.luckywheel.loader;

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
        java.util.Set<String> allowed=new java.util.HashSet<String>(java.util.Arrays.asList("generation.batch-size","generation.mary-max-win-multiplier","generation.mary-min-win-multiplier","generation.max-consecutive-wins","generation.max-members-per-multiplier","generation.normal-count","generation.normal-max-total-multiplier","generation.normal-max-total-win-multiplier","generation.normal-max-win-multiplier","generation.normal-min-win-multiplier","generation.normal-pool-max-win-multiplier","generation.outcome.bet-gte5.multiplier-md1.weight","generation.outcome.bet-gte5.ordinary-loss.weight","generation.outcome.bet-gte5.ordinary-win.weight","generation.outcome.bet-gte5.respin-md2.weight","generation.outcome.bet-gte5.scatter-lucky-wheel-md3.weight","generation.outcome.bet-lt5.multiplier-md1.weight","generation.outcome.bet-lt5.ordinary-loss.weight","generation.outcome.bet-lt5.ordinary-win.weight","generation.outcome.bet-lt5.respin-md2.weight","generation.special-count","generation.special-max-members-per-multiplier","generation.special-max-total-multiplier","generation.special-max-total-win-multiplier","generation.special-max-win-multiplier","generation.special-min-win-multiplier","generation.special-pool-max-win-multiplier","generation.symbol.joint.b1-m0-base00-x1-respinnone-wheelnone.weight","generation.symbol.joint.b1-m0-base01-x1-respinnone-wheelnone.weight","generation.symbol.joint.b1-m0-base41-x1-respinnone-wheelnone.weight","generation.symbol.joint.b1-m0-base43-x1-respinnone-wheelnone.weight","generation.symbol.joint.b1-m0-base44-x1-respinnone-wheelnone.weight","generation.symbol.joint.b1-m0-base51-x1-respinnone-wheelnone.weight","generation.symbol.joint.b1-m0-base53-x1-respinnone-wheelnone.weight","generation.symbol.joint.b1-m0-base54-x1-respinnone-wheelnone.weight","generation.symbol.joint.b1-m1-base00-x2-respinnone-wheelnone.weight","generation.symbol.joint.b1-m1-base00-x5-respinnone-wheelnone.weight","generation.symbol.joint.b1-m1-base01-x2-respinnone-wheelnone.weight","generation.symbol.joint.b1-m1-base01-x5-respinnone-wheelnone.weight","generation.symbol.joint.b1-m1-base33-x5-respinnone-wheelnone.weight","generation.symbol.joint.b1-m1-base34-x5-respinnone-wheelnone.weight","generation.symbol.joint.b1-m1-base41-x2-respinnone-wheelnone.weight","generation.symbol.joint.b1-m1-base41-x5-respinnone-wheelnone.weight","generation.symbol.joint.b1-m1-base43-x2-respinnone-wheelnone.weight","generation.symbol.joint.b1-m1-base43-x5-respinnone-wheelnone.weight","generation.symbol.joint.b1-m1-base44-x2-respinnone-wheelnone.weight","generation.symbol.joint.b1-m1-base44-x5-respinnone-wheelnone.weight","generation.symbol.joint.b1-m1-base50-x5-respinnone-wheelnone.weight","generation.symbol.joint.b1-m1-base51-x2-respinnone-wheelnone.weight","generation.symbol.joint.b1-m1-base51-x5-respinnone-wheelnone.weight","generation.symbol.joint.b1-m1-base53-x2-respinnone-wheelnone.weight","generation.symbol.joint.b1-m1-base54-x2-respinnone-wheelnone.weight","generation.symbol.joint.b1-m2-base00-x1-respin41-wheelnone.weight","generation.symbol.joint.b1-m2-base00-x1-respin43-wheelnone.weight","generation.symbol.joint.b1-m2-base00-x1-respin44-wheelnone.weight","generation.symbol.joint.b1-m2-base00-x1-respin51-wheelnone.weight","generation.symbol.joint.b1-m2-base00-x1-respin53-wheelnone.weight","generation.symbol.joint.b1-m2-base00-x1-respin54-wheelnone.weight","generation.symbol.joint.b1-m2-base01-x1-respin41-wheelnone.weight","generation.symbol.joint.b1-m2-base01-x1-respin44-wheelnone.weight","generation.symbol.joint.b1-m2-base01-x1-respin51-wheelnone.weight","generation.symbol.joint.b1-m2-base03-x1-respin53-wheelnone.weight","generation.symbol.joint.b1-m2-base30-x1-respin53-wheelnone.weight","generation.symbol.joint.b1-m2-base40-x1-respin54-wheelnone.weight","generation.symbol.joint.b1-m2-base51-x1-respin04-wheelnone.weight","generation.symbol.joint.b1-m2-base54-x1-respin04-wheelnone.weight","generation.symbol.joint.b5-m0-base000-x1-respinnone-wheelnone.weight","generation.symbol.joint.b5-m0-base001-x1-respinnone-wheelnone.weight","generation.symbol.joint.b5-m0-base010-x1-respinnone-wheelnone.weight","generation.symbol.joint.b5-m0-base441-x1-respinnone-wheelnone.weight","generation.symbol.joint.b5-m0-base514-x1-respinnone-wheelnone.weight","generation.symbol.joint.b5-m1-base000-x2-respinnone-wheelnone.weight","generation.symbol.joint.b5-m1-base010-x2-respinnone-wheelnone.weight","generation.symbol.joint.b5-m1-base434-x2-respinnone-wheelnone.weight","generation.symbol.joint.b5-m1-base441-x2-respinnone-wheelnone.weight","generation.symbol.joint.b5-m1-base531-x2-respinnone-wheelnone.weight","generation.symbol.joint.b5-m1-base533-x2-respinnone-wheelnone.weight","generation.symbol.joint.b5-m2-base501-x1-respin341-wheelnone.weight","generation.symbol.joint.b5-m3-base411-x1-respinnone-wheel50.weight","generation.symbol.joint.b5-m3-base443-x1-respinnone-wheel150.weight","range.normal-max","range.normal-min","range.special-max","range.special-min","redis.connect-timeout-ms","redis.database","redis.game-id","redis.host","redis.password","redis.port","redis.socket-timeout-ms","redis.ssl","redis.username","retention.special-per-multiplier"));
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
