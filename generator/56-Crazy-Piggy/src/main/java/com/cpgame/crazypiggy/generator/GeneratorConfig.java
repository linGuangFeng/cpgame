package com.cpgame.crazypiggy.generator;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/** 只接受正式 Redis Loader 实际读取的配置；拒绝 seed、场景控制和 JSONL。 */
public final class GeneratorConfig {
    final LoaderLimits outputLimits;
    final String host;
    final int port;
    final String username;
    final String password;
    final int database;
    final boolean ssl;
    final int connectTimeoutMs;
    final int socketTimeoutMs;
    final long redisGameId;
    final int batchSize;
    final int maxMembersPerMultiplier;
    final int maxConsecutiveWins;
    final GenerationWeights weights;

    private GeneratorConfig(Properties p) {
        outputLimits = new LoaderLimits(p);
        host = required(p, "redis.host");
        port = integer(p, "redis.port", 1, 65_535);
        username = p.getProperty("redis.username", "").trim();
        password = p.getProperty("redis.password", "");
        database = integer(p, "redis.database", 0, Integer.MAX_VALUE);
        ssl = bool(p, "redis.ssl");
        connectTimeoutMs = integer(p, "redis.connect-timeout-ms", 1, Integer.MAX_VALUE);
        socketTimeoutMs = integer(p, "redis.socket-timeout-ms", 1, Integer.MAX_VALUE);
        redisGameId = longValue(p, "redis.game-id", 1, 999_999_999L);
        if (redisGameId != 8_000_056L) throw new IllegalArgumentException("redis.game-id must be 8000056");
        batchSize = integer(p, "generation.batch-size", 1, 10_000);
        maxMembersPerMultiplier = integer(p, "generation.max-members-per-multiplier", 1, 1_000_000);
        maxConsecutiveWins = integer(p, "generation.max-consecutive-wins", 1, 7);
        required(p, "generation.normal-min-win-multiplier");
        required(p, "generation.normal-max-win-multiplier");
        if (outputLimits.ordinaryCap != maxMembersPerMultiplier)
            throw new IllegalArgumentException("generation.max-members-per-multiplier conflict");
        weights = GenerationWeights.defaults();
    }

    public static GeneratorConfig load(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file))
            throw new IllegalArgumentException("找不到 generator.properties: " + file);
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(reader); LoaderLimits.checkKeys(p); }
        for (String key : p.stringPropertyNames())
            if (key.toLowerCase(Locale.ROOT).contains("seed"))
                throw new IllegalArgumentException("正式配置禁止 seed: " + key);
        return new GeneratorConfig(p);
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

    private static boolean bool(Properties p, String key) {
        String value = required(p, key);
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value))
            throw new IllegalArgumentException(key + " 必须为 true 或 false");
        return Boolean.parseBoolean(value);
    }
}
