package com.cpgame.curupira.config;

import com.cpgame.curupira.core.GameRules;
import com.cpgame.curupira.core.GenerationPolicy;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/** 只接收代码真实使用的正式 Redis 生成参数。 */
public record EngineConfiguration(
        GenerationPolicy generationPolicy, int normalCount, int specialCount, int batchSize,
        int maxMembersPerMultiplier, int normalMaxWinMultiplier, int specialMaxWinMultiplier,
        int validationSamples, double minimumFirstSuccessRate, String redisHost, int redisPort,
        String redisUsername, String redisPassword, int redisDatabase, boolean redisSsl,
        int redisConnectTimeoutMs, int redisSocketTimeoutMs, int redisGameId, LoaderLimits outputLimits) {
        public EngineConfiguration(
        GenerationPolicy generationPolicy, int normalCount, int specialCount, int batchSize,
        int maxMembersPerMultiplier, int normalMaxWinMultiplier, int specialMaxWinMultiplier,
        int validationSamples, double minimumFirstSuccessRate, String redisHost, int redisPort,
        String redisUsername, String redisPassword, int redisDatabase, boolean redisSsl,
        int redisConnectTimeoutMs, int redisSocketTimeoutMs, int redisGameId) { this(generationPolicy, normalCount, specialCount, batchSize, maxMembersPerMultiplier, normalMaxWinMultiplier, specialMaxWinMultiplier, validationSamples, minimumFirstSuccessRate, redisHost, redisPort, redisUsername, redisPassword, redisDatabase, redisSsl, redisConnectTimeoutMs, redisSocketTimeoutMs, redisGameId, new LoaderLimits(new java.util.Properties())); }


    public static final int MAX_GENERATION_TARGET = Integer.MAX_VALUE;

    private static final Set<String> FIXED_KEYS = Set.of(
            "redis.host", "redis.port", "redis.username", "redis.password", "redis.database", "redis.ssl",
            "redis.connect-timeout-ms", "redis.socket-timeout-ms", "redis.game-id",
            "generation.normal-count", "generation.special-count", "generation.batch-size",
            "generation.max-members-per-multiplier", "generation.special-max-members-per-multiplier", "generation.normal-min-win-multiplier", "generation.special-min-win-multiplier",
            "generation.max-consecutive-wins",
            "generation.max-special-steps", "generation.normal-max-win-multiplier",
            "generation.special-max-win-multiplier", "generation.loss.construction-attempts",
            "generation.loss.fallback-samples", "generation.loss.validation-samples",
            "generation.loss.minimum-first-success-rate");

    public static EngineConfiguration load(Path path) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { properties.load(reader); LoaderLimits.checkKeys(properties); }
        return from(properties);
    }

    public static EngineConfiguration from(Properties p) {
        if (p.stringPropertyNames().stream().anyMatch(k -> k.toLowerCase().contains("seed"))) {
            throw new IllegalArgumentException("正式配置禁止 seed");
        }
        Set<String> unsupported = new TreeSet<>();
        for (String key : p.stringPropertyNames()) {
            if (!FIXED_KEYS.contains(key) && !key.matches("generation\\.symbol\\.[0-9]+\\.normal-weight")) {
                unsupported.add(key);
            }
        }
        if (!unsupported.isEmpty()) throw new IllegalArgumentException("正式配置包含未读取参数：" + unsupported);
        Map<Integer, Integer> weights = new LinkedHashMap<>();
        for (int id : GameRules.SYMBOLS.stream().sorted().toList()) {
            weights.put(id, integer(p, "generation.symbol." + id + ".normal-weight"));
        }
        if (weights.values().stream().anyMatch(weight -> weight <= 0)) {
            throw new IllegalArgumentException("正式牌面权重必须全部为正数");
        }
        GenerationPolicy policy = new GenerationPolicy(weights,
                integer(p, "generation.loss.construction-attempts"),
                integer(p, "generation.loss.fallback-samples"),
                integer(p, "generation.normal-max-win-multiplier"),
                integer(p, "generation.max-special-steps"),
                integer(p, "generation.max-consecutive-wins"));
        EngineConfiguration result = new EngineConfiguration(policy,
                target(p, "generation.normal-count"), target(p, "generation.special-count"),
                integer(p, "generation.batch-size"), integer(p, "generation.max-members-per-multiplier"),
                integer(p, "generation.normal-max-win-multiplier"), integer(p, "generation.special-max-win-multiplier"),
                integer(p, "generation.loss.validation-samples"),
                decimal(p, "generation.loss.minimum-first-success-rate"), required(p, "redis.host"),
                integer(p, "redis.port"), p.getProperty("redis.username", "").trim(),
                p.getProperty("redis.password", ""), integer(p, "redis.database"),
                Boolean.parseBoolean(required(p, "redis.ssl")), integer(p, "redis.connect-timeout-ms"),
                integer(p, "redis.socket-timeout-ms"), integer(p, "redis.game-id"), new LoaderLimits(p));
        if (result.redisPort < 1 || result.redisPort > 65535 || result.redisDatabase < 0
                || result.redisGameId < 1 || result.normalCount < 0 || result.specialCount < 0
                || result.normalCount > MAX_GENERATION_TARGET || result.specialCount > MAX_GENERATION_TARGET
                || (long) result.normalCount + result.specialCount < 1 || result.batchSize < 1
                || result.maxMembersPerMultiplier < 1 || result.normalMaxWinMultiplier < 1
                || result.specialMaxWinMultiplier < 1 || result.validationSamples < 1
                || result.minimumFirstSuccessRate <= 0 || result.minimumFirstSuccessRate > 1) {
            throw new IllegalArgumentException("generator.properties 参数范围无效");
        }
        return result;
    }

    private static int target(Properties p, String key) {
        String raw = required(p, key);
        try {
            int value = Integer.parseInt(raw);
            if (value >= 0) return value;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(key + " must be 0.." + MAX_GENERATION_TARGET + ", actual=" + raw);
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null) throw new IllegalArgumentException("缺少参数：" + key);
        return value.trim();
    }
    private static int integer(Properties p, String key) { return Integer.parseInt(required(p, key)); }
    private static double decimal(Properties p, String key) { return Double.parseDouble(required(p, key)); }
}
