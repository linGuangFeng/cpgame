package com.cpgame.saci.generator;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

public final class GeneratorConfig {
    final LoaderLimits outputLimits;
    private static final int MAX_TARGET = Integer.MAX_VALUE;
    private static final Set<String> FIXED_KEYS = Set.of(
            "redis.host", "redis.port", "redis.username", "redis.password", "redis.database", "redis.ssl",
            "redis.connect-timeout-ms", "redis.socket-timeout-ms", "redis.game-id",
            "generation.normal-count", "generation.loss-count", "generation.win-count", "generation.special-count", "generation.batch-size",
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

    private GeneratorConfig(Properties p) {
        outputLimits = new LoaderLimits(p);
        rejectUnknownKeys(p);
        if (p.containsKey("generation.normal-count")) {
            if(p.containsKey("generation.loss-count")||p.containsKey("generation.win-count"))throw new IllegalArgumentException("Use normal-count OR loss-count/win-count, not both");
            int total=integer(p,"generation.normal-count",0,MAX_TARGET);
            int loss=outputLimits.accepts(false,0)?total/2:0;
            p.setProperty("generation.loss-count",Integer.toString(loss));p.setProperty("generation.win-count",Integer.toString(total-loss));
        }
        host = required(p, "redis.host");
        port = integer(p, "redis.port", 1, 65_535);
        username = p.getProperty("redis.username", "").trim();
        password = p.getProperty("redis.password", "");
        database = integer(p, "redis.database", 0, Integer.MAX_VALUE);
        ssl = bool(p, "redis.ssl");
        connectTimeoutMs = integer(p, "redis.connect-timeout-ms", 1, Integer.MAX_VALUE);
        socketTimeoutMs = integer(p, "redis.socket-timeout-ms", 1, Integer.MAX_VALUE);
        redisGameId = longValue(p, "redis.game-id", 1, 999_999_999L);
        lossCount = integer(p, "generation.loss-count", 0, MAX_TARGET);
        winCount = integer(p, "generation.win-count", 0, MAX_TARGET);
        specialCount = integer(p, "generation.special-count", 0, MAX_TARGET);
        batchSize = integer(p, "generation.batch-size", 1, 10_000);
        maxMembersPerMultiplier = integer(p, "generation.max-members-per-multiplier", 1, 1_000_000);
        maxConsecutiveWins = integer(p, "generation.max-consecutive-wins", 1, 80);
        normalMaxWinMultiplier = decimal(p, "generation.normal-max-win-multiplier");
        specialMaxWinMultiplier = decimal(p, "generation.special-max-win-multiplier");
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
        for (String key : p.stringPropertyNames()) {
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.contains("seed")) throw new IllegalArgumentException("正式配置禁止 seed: " + key);
            if (!FIXED_KEYS.contains(key)) throw new IllegalArgumentException("配置项未被正式代码读取或已禁止: " + key);
        }
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
