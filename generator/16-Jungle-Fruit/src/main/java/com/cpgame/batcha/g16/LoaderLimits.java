package com.cpgame.batcha.g16;

import java.math.BigDecimal;
import java.util.Properties;

/** Immutable Redis output limits. Zero is subject to the same inclusive range. */
public final class LoaderLimits {
    private final BigDecimal normalMin, normalMax, specialMin, specialMax;
    public final int specialCap;
    public final int batchSize;
    public final String username;
    public final boolean ssl;
    public final int normalCount, specialCount;
    public final java.util.Map<String, Double> symbolWeights;
    public static boolean extraKey(String key) {
        return java.util.Set.of("generation.batch-size", "generation.normal-count", "generation.special-count").contains(key)
            || weightKey(key);
    }
    private static boolean weightKey(String key) {
        if (!key.startsWith("generation.symbol.")) return false;
        String[] parts = key.split("\\.");
        return parts.length == 4 && SYMBOLS.contains(parts[2])
            && java.util.Set.of("normal-weight", "special-weight").contains(parts[3]);
    }
    private static final java.util.Set<String> SYMBOLS = java.util.Set.of("A","H1","H2","H3","H4","H5","J","K","Q","T","Scat","X2","X3","X4","X5","X7","X15");

    public LoaderLimits(Properties p) {
        batchSize = number(p, "100", "generation.batch-size").intValueExact();
        username = p.getProperty("redis.username", "").strip();
        ssl = Boolean.parseBoolean(p.getProperty("redis.ssl", "false").strip());
        normalCount = number(p, "-1", "generation.normal-count").intValueExact();
        specialCount = number(p, "-1", "generation.special-count").intValueExact();
        if (batchSize < 1 || batchSize > 10000 || normalCount < -1 || specialCount < -1
            || (normalCount == -1) != (specialCount == -1)
            || (normalCount >= 0 && (long)normalCount + specialCount < 1))
            throw new IllegalArgumentException("Invalid batch size or normal/special counts (configure both counts together)");
        java.util.Map<String, Double> weights = new java.util.HashMap<>();
        for (String key : p.stringPropertyNames()) if (weightKey(key)) {
            double weight = number(p, "1", key).doubleValue();
            if (!Double.isFinite(weight) || weight < 0 || weight > Integer.MAX_VALUE)
                throw new IllegalArgumentException(key + " must be >= 0 and <= Integer.MAX_VALUE");
            weights.put(key, weight);
        }
        symbolWeights = java.util.Map.copyOf(weights);
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
        java.util.Set<String> allowed=new java.util.HashSet<String>(java.util.Arrays.asList("game.raw-id","generation.bet-level","generation.bet-size","generation.mary-max-win-multiplier","generation.mary-min-win-multiplier","generation.max-candidates","generation.max-members-per-multiplier","generation.maximum-cascades","generation.maximum-round-multiplier","generation.maximum-special-spins","generation.modes","generation.normal-max-total-multiplier","generation.normal-max-total-win-multiplier","generation.normal-max-win-multiplier","generation.normal-min-win-multiplier","generation.normal-pool-max-win-multiplier","generation.special-max-members-per-multiplier","generation.special-max-total-multiplier","generation.special-max-total-win-multiplier","generation.special-max-win-multiplier","generation.special-min-win-multiplier","generation.special-pool-max-win-multiplier","generation.total-members","range.normal-max","range.normal-min","range.special-max","range.special-min","redis.connect-timeout-ms","redis.database","redis.game-id","redis.host","redis.password","redis.port","redis.read-timeout-ms","retention.special-per-multiplier"));
        allowed.addAll(java.util.List.of("redis.username", "redis.ssl", "redis.socket-timeout-ms"));
        for(String key:p.stringPropertyNames())if(!allowed.contains(key) && !extraKey(key)){if(key.toLowerCase(java.util.Locale.ROOT).contains("seed"))throw new IllegalArgumentException("正式配置禁止 seed: "+key);System.err.println("[warn] unused generator.properties key: "+key);};
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
