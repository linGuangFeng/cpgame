package com.cpgame.coinmastergo.generator;

import com.cpgame.coinmastergo.core.GameRules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** 正式 Loader 唯一配置模型；不接受 seed、关闭 Redis 或强制场景参数。 */
public final class GeneratorConfiguration {
    public final LoaderLimits outputLimits;
    static final int MAX_GENERATION_TARGET = Integer.MAX_VALUE;
    private static final Set<String> FIXED_KEYS = Set.of(
            "redis.host", "redis.port", "redis.username", "redis.password", "redis.database", "redis.ssl",
            "redis.connect-timeout-ms", "redis.socket-timeout-ms", "redis.game-id",
            "generation.normal-count", "generation.special-count", "generation.batch-size",
            "generation.max-members-per-multiplier", "generation.special-max-members-per-multiplier",
            "generation.max-consecutive-wins",
            "generation.max-mode-spins.freeSpins",
            "generation.normal-min-win-multiplier", "generation.normal-max-win-multiplier",
            "generation.special-min-win-multiplier", "generation.special-max-win-multiplier",
            "generation.card.silver.weight", "generation.card.gold.weight");

    final String redisHost;
    final int redisPort;
    final String redisUsername;
    final String redisPassword;
    final int redisDatabase;
    final boolean redisSsl;
    final int connectTimeoutMs;
    final int socketTimeoutMs;
    final long redisGameId;
    final int normalCount;
    final int specialCount;
    final int batchSize;
    final int maxMembersPerMultiplier;
    final int maxConsecutiveWins;
    final int maxFreeSpins;
    final int normalMinWinMultiplier;
    final int specialMinWinMultiplier;
    final int normalMaxWinMultiplier;
    final int specialMaxWinMultiplier;
    final Map<String, Integer> symbolWeights;
    final int silverCardWeight;
    final int goldCardWeight;

    private GeneratorConfiguration(Properties values) {
        outputLimits = new LoaderLimits(values);
        rejectUnknown(values);
        redisHost = required(values, "redis.host");
        redisPort = integer(values, "redis.port", 1, 65_535);
        redisUsername = values.getProperty("redis.username", "").trim();
        redisPassword = values.getProperty("redis.password", "");
        redisDatabase = integer(values, "redis.database", 0, Integer.MAX_VALUE);
        redisSsl = bool(values, "redis.ssl");
        connectTimeoutMs = integer(values, "redis.connect-timeout-ms", 1, 300_000);
        socketTimeoutMs = integer(values, "redis.socket-timeout-ms", 1, 3_600_000);
        redisGameId = longValue(values, "redis.game-id", 1, Long.MAX_VALUE);
        normalCount = integer(values, "generation.normal-count", 0, MAX_GENERATION_TARGET);
        specialCount = integer(values, "generation.special-count", 0, MAX_GENERATION_TARGET);
        if (normalCount + specialCount == 0) throw new IllegalArgumentException("普通/特殊生成目标不能同时为 0");
        batchSize = integer(values, "generation.batch-size", 1, 10_000);
        maxMembersPerMultiplier = integer(values, "generation.max-members-per-multiplier", 1, 1_000_000);
        maxConsecutiveWins = integer(values, "generation.max-consecutive-wins", 1, 100);
        maxFreeSpins = integer(values, "generation.max-mode-spins.freeSpins", 1, 10_000);
        normalMinWinMultiplier = integer(values, "generation.normal-min-win-multiplier", 0, Integer.MAX_VALUE);
        specialMinWinMultiplier = integer(values, "generation.special-min-win-multiplier", 0, Integer.MAX_VALUE);
        normalMaxWinMultiplier = integer(values, "generation.normal-max-win-multiplier", 1, Integer.MAX_VALUE);
        specialMaxWinMultiplier = integer(values, "generation.special-max-win-multiplier", 1, Integer.MAX_VALUE);
        if (normalMinWinMultiplier > normalMaxWinMultiplier || specialMinWinMultiplier > specialMaxWinMultiplier) {
            throw new IllegalArgumentException("普通/特殊最小倍数不能大于最大倍数");
        }
        LinkedHashMap<String, Integer> weights = new LinkedHashMap<>();
        for (String symbol : symbolOrder()) {
            int minimum = "WILD".equals(symbol) ? 0 : 1;
            weights.put(symbol, integer(values, weightKey(symbol), minimum, Integer.MAX_VALUE));
        }
        if (weights.get("WILD") != 0) {
            throw new IllegalArgumentException("generation.symbol.WILD.weight 必须为0；WILD只能由金牌变出");
        }
        symbolWeights = Map.copyOf(weights);
        silverCardWeight = integer(values, "generation.card.silver.weight", 0, Integer.MAX_VALUE);
        goldCardWeight = integer(values, "generation.card.gold.weight", 0, Integer.MAX_VALUE);
        if (silverCardWeight == 0 && goldCardWeight == 0) {
            throw new IllegalArgumentException("银牌/金牌生成权重不能同时为0");
        }
        if ((long) silverCardWeight + goldCardWeight > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("银牌/金牌生成权重总和过大");
        }
    }

    public static GeneratorConfiguration load(Path path) {
        Properties values = new Properties();
        try (var reader = Files.newBufferedReader(path.toAbsolutePath().normalize(), StandardCharsets.UTF_8)) {
            values.load(reader); LoaderLimits.checkKeys(values);
        } catch (IOException error) {
            throw new IllegalArgumentException("无法读取生成器配置：" + path, error);
        }
        return new GeneratorConfiguration(values);
    }

    static GeneratorConfiguration from(Properties values) { return new GeneratorConfiguration(values); }

    int minimumMultiplier(boolean special) {
        return special ? specialMinWinMultiplier : normalMinWinMultiplier;
    }

    int maximumMultiplier(boolean special) {
        return special ? specialMaxWinMultiplier : normalMaxWinMultiplier;
    }

    static String weightKey(String symbol) { return "generation.symbol." + symbol + ".weight"; }

    static java.util.List<String> symbolOrder() {
        return java.util.List.of("H1", "H2", "H3", "H4", "H5", "H6", "H7", "H8", "WILD", "SC");
    }

    private static void rejectUnknown(Properties values) {
        Set<String> allowed = new java.util.HashSet<>(FIXED_KEYS);
        for (String symbol : symbolOrder()) allowed.add(weightKey(symbol));
        for (String key : values.stringPropertyNames()) {
            if (!allowed.contains(key)) throw new IllegalArgumentException("未知或不允许的配置项：" + key);
        }
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少配置项：" + key);
        return value.trim();
    }

    private static int integer(Properties p, String key, int minimum, int maximum) {
        int value = Integer.parseInt(required(p, key));
        if (value < minimum || value > maximum) throw new IllegalArgumentException(key + " 超出范围");
        return value;
    }

    private static long longValue(Properties p, String key, long minimum, long maximum) {
        long value = Long.parseLong(required(p, key));
        if (value < minimum || value > maximum) throw new IllegalArgumentException(key + " 超出范围");
        return value;
    }

    private static boolean bool(Properties p, String key) {
        String value = required(p, key);
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException(key + " 必须是 true 或 false");
        }
        return Boolean.parseBoolean(value);
    }

}
