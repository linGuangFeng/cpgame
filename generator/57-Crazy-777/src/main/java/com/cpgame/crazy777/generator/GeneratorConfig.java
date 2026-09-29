package com.cpgame.crazy777.generator;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** 只接受正式 Redis Loader 实际读取的配置；拒绝 seed、场景控制和 JSONL。 */
public final class GeneratorConfig {
    final LoaderLimits outputLimits;
    private static final int MAX_TARGET = Integer.MAX_VALUE;
    private static final Set<String> FIXED_KEYS = Set.of(
            "redis.host", "redis.port", "redis.username", "redis.password", "redis.database", "redis.ssl",
            "redis.connect-timeout-ms", "redis.socket-timeout-ms", "redis.game-id",
            "generation.loss-count", "generation.win-count", "generation.special-count", "generation.batch-size",
            "generation.max-members-per-multiplier", "generation.special-max-members-per-multiplier", "generation.normal-min-win-multiplier", "generation.special-min-win-multiplier",
            "generation.max-consecutive-wins",
            "generation.normal-max-win-multiplier", "generation.special-max-win-multiplier");

    final String host;
    final int port;
    final String username;
    final String password;
    final int database;
    final boolean ssl;
    final int connectTimeoutMs;
    final int socketTimeoutMs;
    final long redisGameId;
    final int lossCount;
    final int winCount;
    final int specialCount;
    final int batchSize;
    final int maxMembersPerMultiplier;
    final int maxConsecutiveWins;
    final BigDecimal normalMaxWinMultiplier;
    final BigDecimal specialMaxWinMultiplier;
    final SymbolWeights symbolWeights;

    private GeneratorConfig(Properties p) {
        outputLimits = new LoaderLimits(p);
        rejectUnknownKeys(p);
        host = required(p, "redis.host");
        port = integer(p, "redis.port", 1, 65_535);
        username = p.getProperty("redis.username", "").trim();
        password = p.getProperty("redis.password", "");
        database = integer(p, "redis.database", 0, Integer.MAX_VALUE);
        ssl = bool(p, "redis.ssl");
        connectTimeoutMs = integer(p, "redis.connect-timeout-ms", 1, Integer.MAX_VALUE);
        socketTimeoutMs = integer(p, "redis.socket-timeout-ms", 1, Integer.MAX_VALUE);
        redisGameId = longValue(p, "redis.game-id", 1, 999_999_999L);
        if (redisGameId != 8_000_057L) throw new IllegalArgumentException("redis.game-id must be 8000057");
        lossCount = integer(p, "generation.loss-count", 1, MAX_TARGET);
        winCount = integer(p, "generation.win-count", 1, MAX_TARGET);
        specialCount = integer(p, "generation.special-count", 1, MAX_TARGET);
        batchSize = integer(p, "generation.batch-size", 1, 10_000);
        maxMembersPerMultiplier = integer(p, "generation.max-members-per-multiplier", 1, 1_000_000);
        maxConsecutiveWins = integer(p, "generation.max-consecutive-wins", 1, 11);
        normalMaxWinMultiplier = decimal(p, "generation.normal-max-win-multiplier");
        specialMaxWinMultiplier = decimal(p, "generation.special-max-win-multiplier");
        symbolWeights = new SymbolWeights(weights(p, "normal", SymbolWeights.ALL),
                weights(p, "entry", SymbolWeights.ALL), weights(p, "free", SymbolWeights.FREE));
    }

    public static GeneratorConfig load(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("找不到 generator.properties: " + file);
        }
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(reader); LoaderLimits.checkKeys(p); }
        return new GeneratorConfig(p);
    }

    private static void rejectUnknownKeys(Properties p) {
        Set<String> allowed = new LinkedHashSet<>(FIXED_KEYS);
        for (String symbol : SymbolWeights.ALL) {
            allowed.add("generation.symbol." + symbol + ".normal-weight");
            allowed.add("generation.symbol." + symbol + ".entry-weight");
        }
        for (String symbol : SymbolWeights.FREE) allowed.add("generation.symbol." + symbol + ".free-weight");
        for (String key : p.stringPropertyNames()) {
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.contains("seed")) throw new IllegalArgumentException("正式配置禁止 seed: " + key);
            if (!allowed.contains(key)) {if(key.toLowerCase(java.util.Locale.ROOT).contains("seed"))throw new IllegalArgumentException("正式配置禁止 seed: "+key);System.err.println("[warn] unused generator.properties key: "+key);};
        }
    }

    private static Map<String, Integer> weights(Properties p, String mode, java.util.List<String> symbols) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String symbol : symbols)
            result.put(symbol, integer(p, "generation.symbol." + symbol + "." + mode + "-weight", 1, Integer.MAX_VALUE));
        return result;
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("缺少配置: " + key);
        return value.trim();
    }

    private static int integer(Properties p, String key, int min, int max) {
        int value;
        try { value = Integer.parseInt(required(p, key)); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(key + " 必须为整数", ex); }
        if (value < min || value > max) throw new IllegalArgumentException(key + " 超出范围 " + min + ".." + max);
        return value;
    }

    private static long longValue(Properties p, String key, long min, long max) {
        long value;
        try { value = Long.parseLong(required(p, key)); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(key + " 必须为整数", ex); }
        if (value < min || value > max) throw new IllegalArgumentException(key + " 超出范围 " + min + ".." + max);
        return value;
    }

    private static BigDecimal decimal(Properties p, String key) {
        BigDecimal value;
        try { value = new BigDecimal(required(p, key)); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(key + " 必须为正数", ex); }
        if (value.signum() <= 0) throw new IllegalArgumentException(key + " 必须为正数");
        return value.stripTrailingZeros();
    }

    private static boolean bool(Properties p, String key) {
        String value = required(p, key);
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(key + " 必须为 true 或 false");
        }
        return Boolean.parseBoolean(value);
    }
}
