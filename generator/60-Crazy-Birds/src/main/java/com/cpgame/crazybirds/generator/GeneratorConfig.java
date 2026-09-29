package com.cpgame.crazybirds.generator;

import com.cpgame.crazybirds.generator.model.RoundMode;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/** 最新生成合同：总尝试数、尝试批次、显式权重和有界桶容量。 */
public final class GeneratorConfig {
    private static final Set<String> FIXED_KEYS = Set.of(
            "redis.host", "redis.port", "redis.username", "redis.password", "redis.database", "redis.ssl",
            "redis.connect-timeout-ms", "redis.socket-timeout-ms", "redis.game-id",
            "generation.count", "generation.batch-size",
            "generation.normal-min-win-multiplier", "generation.normal-max-win-multiplier",
            "generation.free-min-win-multiplier", "generation.free-max-win-multiplier",
            "weights.source", "weights.symbol-order",
            "retention.normal-per-multiplier", "retention.mary-per-multiplier");

    final GenerationWeights weights;
    final RetentionPolicy retention;
    final String host;
    final int port;
    final String username;
    final String password;
    final int database;
    final boolean ssl;
    final int connectTimeoutMs;
    final int socketTimeoutMs;
    final long redisGameId;
    final long generationCount;
    final int batchSize;
    final int normalMinCacheMultiplier;
    final int normalMaxCacheMultiplier;
    final int freeMinCacheMultiplier;
    final int freeMaxCacheMultiplier;

    private GeneratorConfig(Properties p) {
        rejectUnknownKeys(p);
        weights = new GenerationWeights(p);
        retention = new RetentionPolicy(p);
        host = required(p, "redis.host");
        port = integer(p, "redis.port", 1, 65_535);
        username = p.getProperty("redis.username", "").trim();
        password = p.getProperty("redis.password", "");
        database = integer(p, "redis.database", 0, Integer.MAX_VALUE);
        ssl = bool(p, "redis.ssl");
        connectTimeoutMs = integer(p, "redis.connect-timeout-ms", 1, Integer.MAX_VALUE);
        socketTimeoutMs = integer(p, "redis.socket-timeout-ms", 1, Integer.MAX_VALUE);
        redisGameId = longValue(p, "redis.game-id", 1, 99_999_999L);
        generationCount = longValue(p, "generation.count", 1, Long.MAX_VALUE);
        batchSize = integer(p, "generation.batch-size", 1, 1_000_000);
        normalMinCacheMultiplier = integer(p, "generation.normal-min-win-multiplier", 0, Integer.MAX_VALUE);
        normalMaxCacheMultiplier = integer(p, "generation.normal-max-win-multiplier", 1, Integer.MAX_VALUE);
        freeMinCacheMultiplier = integer(p, "generation.free-min-win-multiplier", 0, Integer.MAX_VALUE);
        freeMaxCacheMultiplier = integer(p, "generation.free-max-win-multiplier", 1, Integer.MAX_VALUE);
        if (normalMaxCacheMultiplier < normalMinCacheMultiplier
                || freeMaxCacheMultiplier < freeMinCacheMultiplier) {
            throw new IllegalArgumentException("生成倍率范围上下界错误");
        }
    }

    public static GeneratorConfig load(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("找不到 generator.properties: " + file);
        }
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(reader); }
        return new GeneratorConfig(p);
    }

    boolean acceptsCacheMultiplier(RoundMode mode, int cacheMultiplier) {
        if (cacheMultiplier < 0) throw new IllegalArgumentException("缓存倍率不能为负数");
        int min = mode == RoundMode.FREE_SPINS ? freeMinCacheMultiplier : normalMinCacheMultiplier;
        int max = mode == RoundMode.FREE_SPINS ? freeMaxCacheMultiplier : normalMaxCacheMultiplier;
        return cacheMultiplier >= min && cacheMultiplier <= max;
    }

    private static void rejectUnknownKeys(Properties p) {
        for (String key : p.stringPropertyNames()) {
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.contains("seed")) throw new IllegalArgumentException("正式配置禁止 seed: " + key);
            if (FIXED_KEYS.contains(key) || RetentionPolicy.recognizes(key) || weightKey(key)) continue;
            throw new IllegalArgumentException("配置项未被正式代码读取或已禁止: " + key);
        }
    }

    private static boolean weightKey(String key) {
        for (String symbol : GameRules.ALL_SYMBOLS) {
            if (("weights.base.paid." + symbol).equals(key)
                    || ("weights.base.free." + symbol).equals(key)
                    || ("weights.boost." + symbol).equals(key)) return true;
        }
        return false;
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少配置: " + key);
        return value.trim();
    }

    private static int integer(Properties p, String key, int min, int max) {
        long value = longValue(p, key, min, max);
        return Math.toIntExact(value);
    }

    private static long longValue(Properties p, String key, long min, long max) {
        long value;
        try { value = Long.parseLong(required(p, key)); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(key + " 必须为整数", ex); }
        if (value < min || value > max) throw new IllegalArgumentException(key + " 超出范围 " + min + ".." + max);
        return value;
    }

    private static boolean bool(Properties p, String key) {
        String value = required(p, key);
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(key + " 必须为 true 或 false");
        }
        return Boolean.parseBoolean(value);
    }
}
