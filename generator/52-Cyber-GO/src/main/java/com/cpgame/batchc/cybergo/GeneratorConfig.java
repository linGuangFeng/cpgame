package com.cpgame.batchc.cybergo;

import static com.cpgame.batchc.cybergo.CyberGoRules.*;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** 只读取正式生成和 Redis 直接写入真正使用的参数；不接受场景轮播或旁路开关。 */
public final class GeneratorConfig {
    final LoaderLimits outputLimits;
    private static final int MAX_GENERATION_TARGET = Integer.MAX_VALUE;
    private static final Set<String> FIXED_KEYS = Set.of(
            "redis.host", "redis.port", "redis.username", "redis.password", "redis.database", "redis.ssl",
            "redis.connect-timeout-ms", "redis.socket-timeout-ms", "redis.game-id",
            "generation.normal-count", "generation.special-count", "generation.loss-count", "generation.batch-size",
            "generation.max-members-per-multiplier", "generation.special-max-members-per-multiplier",
            "generation.max-consecutive-wins",
            "generation.normal-min-win-multiplier", "generation.normal-max-win-multiplier",
            "generation.special-min-win-multiplier", "generation.special-max-win-multiplier",
            "generation.special-max-steps");

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
    final int normalCount;
    final int specialCount;
    final int batchSize;
    final int maxMembersPerMultiplier;
    final int maxConsecutiveWins;
    final BigDecimal normalMaxWinMultiplier;
    final BigDecimal specialMaxWinMultiplier;
    final int specialMaxSteps;
    final SymbolWeights symbolWeights;

    private GeneratorConfig(Properties properties) {
        outputLimits = new LoaderLimits(properties);
        rejectUnknownKeys(properties);
        host = required(properties, "redis.host");
        port = integer(properties, "redis.port", 1, 65_535);
        username = properties.getProperty("redis.username", "").trim();
        password = properties.getProperty("redis.password", "");
        database = integer(properties, "redis.database", 0, Integer.MAX_VALUE);
        ssl = bool(properties, "redis.ssl");
        connectTimeoutMs = integer(properties, "redis.connect-timeout-ms", 1, Integer.MAX_VALUE);
        socketTimeoutMs = integer(properties, "redis.socket-timeout-ms", 1, Integer.MAX_VALUE);
        redisGameId = longValue(properties, "redis.game-id", 1, 999_999_999);
        lossCount = integer(properties, "generation.loss-count", 0, MAX_GENERATION_TARGET);
        normalCount = integer(properties, "generation.normal-count", 0, MAX_GENERATION_TARGET);
        specialCount = integer(properties, "generation.special-count", 0, MAX_GENERATION_TARGET);
        if (normalCount == 0 && specialCount == 0 && lossCount == 0) throw new IllegalArgumentException("普通和特殊生成目标不能同时为0");
        batchSize = integer(properties, "generation.batch-size", 1, 10_000);
        maxMembersPerMultiplier = integer(properties, "generation.max-members-per-multiplier", 1, 1_000_000);
        maxConsecutiveWins = integer(properties, "generation.max-consecutive-wins", 1, 10_000);
        normalMaxWinMultiplier = positiveDecimal(properties, "generation.normal-max-win-multiplier");
        specialMaxWinMultiplier = positiveDecimal(properties, "generation.special-max-win-multiplier");
        specialMaxSteps = integer(properties, "generation.special-max-steps", 1, 20);
        symbolWeights = SymbolWeights.localDefaults(); // Compatibility only; sampling uses EmpiricalReelModel.
    }

    public static GeneratorConfig load(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) throw new IllegalArgumentException("找不到 generator.properties: " + file);
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); LoaderLimits.checkKeys(properties); }
        return new GeneratorConfig(properties);
    }

    GenerationLimits limits() {
        return new GenerationLimits(normalMaxWinMultiplier, specialMaxWinMultiplier, specialMaxSteps,
                5, 10, symbolWeights);
    }

    private static Map<String, Integer> weights(Properties properties, boolean normal) {
        String mode = normal ? "normal" : "free";
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String symbol : PAYING_SYMBOLS) {
            String key = "generation.symbol." + symbol + "." + mode + "-weight";
            result.put(symbol, integer(properties, key, 1, Integer.MAX_VALUE));
        }
        result.put(WILD, integer(properties, "generation.symbol.WILD." + mode + "-weight", 1, Integer.MAX_VALUE));
        if (normal) result.put(SCATTER, integer(properties, "generation.symbol.SC.normal-weight", 1, Integer.MAX_VALUE));
        return result;
    }

    private static void rejectUnknownKeys(Properties properties) {
        Set<String> allowed = new LinkedHashSet<>(FIXED_KEYS);
        for (String key : properties.stringPropertyNames()) {
            String lower = key.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("seed")) throw new IllegalArgumentException("正式配置禁止 seed: " + key);
            if (!allowed.contains(key)) throw new IllegalArgumentException("配置项未被正式代码读取: " + key);
        }
        for (String key : allowed) {
            boolean optionalLimit = key.equals("generation.normal-min-win-multiplier")
                    || key.equals("generation.special-min-win-multiplier")
                    || key.equals("generation.special-max-members-per-multiplier");
            if (!optionalLimit && !properties.containsKey(key)) throw new IllegalArgumentException("正式配置缺少参数: " + key);
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("缺少配置: " + key);
        return value.trim();
    }

    private static int integer(Properties properties, String key, int min, int max) {
        int value;
        try { value = Integer.parseInt(required(properties, key)); }
        catch (NumberFormatException error) { throw new IllegalArgumentException(key + "必须是整数", error); }
        if (value < min || value > max) throw new IllegalArgumentException(key + "超出范围" + min + ".." + max);
        return value;
    }

    private static long longValue(Properties properties, String key, long min, long max) {
        long value;
        try { value = Long.parseLong(required(properties, key)); }
        catch (NumberFormatException error) { throw new IllegalArgumentException(key + "必须是整数", error); }
        if (value < min || value > max) throw new IllegalArgumentException(key + "超出范围" + min + ".." + max);
        return value;
    }

    private static BigDecimal positiveDecimal(Properties properties, String key) {
        BigDecimal value;
        try { value = new BigDecimal(required(properties, key)); }
        catch (NumberFormatException error) { throw new IllegalArgumentException(key + "必须是数字", error); }
        if (value.signum() <= 0) throw new IllegalArgumentException(key + "必须大于0");
        return value;
    }

    private static boolean bool(Properties properties, String key) {
        String value = required(properties, key);
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value))
            throw new IllegalArgumentException(key + "必须为true或false");
        return Boolean.parseBoolean(value);
    }
}
