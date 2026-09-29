package com.cpgame.curupira.config;

import com.cpgame.curupira.core.GameRules;
import com.cpgame.curupira.core.GenerationScene;
import com.cpgame.curupira.core.GenerationPolicy;
import com.cpgame.curupira.random.RandomSource;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/** 2350 正式 Loader 的显式配置；未知键和旧的按结果目标计数键一律拒绝。 */
public record EngineConfiguration(
        GenerationPolicy generationPolicy,
        long generationCount,
        int batchSize,
        int normalRetention,
        int maryRetention,
        int normalMinWinMultiplier,
        int normalMaxWinMultiplier,
        int maryMinWinMultiplier,
        int maryMaxWinMultiplier,
        int normalSceneWeight,
        int freeExpandingWildSceneWeight,
        int holdAndSpinsSceneWeight,
        int holdEmptyWeight,
        int holdCoinWeight,
        String weightsSource,
        List<Integer> weightOrder,
        Map<Integer, Integer> boostFactors,
        String redisHost,
        int redisPort,
        String redisUsername,
        String redisPassword,
        int redisDatabase,
        boolean redisSsl,
        int redisConnectTimeoutMs,
        int redisSocketTimeoutMs,
        int redisGameId) {

    private static final Set<String> FIXED_KEYS = Set.of(
            "redis.host", "redis.port", "redis.username", "redis.password", "redis.database", "redis.ssl",
            "redis.connect-timeout-ms", "redis.socket-timeout-ms", "redis.game-id",
            "generation.count", "generation.batch-size",
            "retention.normal-per-multiplier", "retention.mary-per-multiplier",
            "generation.normal-min-win-multiplier", "generation.normal-max-win-multiplier",
            "generation.mary-min-win-multiplier", "generation.mary-max-win-multiplier",
            "generation.scene.normal-weight", "generation.scene.free-expanding-wild-weight",
            "generation.scene.hold-and-spins-weight",
            "generation.hold.empty-weight", "generation.hold.coin-weight",
            "weights.source", "weights.order");

    public EngineConfiguration {
        weightOrder = List.copyOf(weightOrder);
        boostFactors = Map.copyOf(boostFactors);
        if (generationPolicy == null || generationCount < 1 || batchSize < 1
                || normalRetention < 1 || maryRetention < 1
                || normalMinWinMultiplier < 0 || normalMaxWinMultiplier < normalMinWinMultiplier
                || maryMinWinMultiplier < 0 || maryMaxWinMultiplier < maryMinWinMultiplier
                || normalSceneWeight < 1 || freeExpandingWildSceneWeight < 1 || holdAndSpinsSceneWeight < 1
                || holdEmptyWeight < 1 || holdCoinWeight < 1
                || weightsSource == null || weightsSource.isBlank()
                || !weightOrder.equals(GameRules.SYMBOL_ORDER)
                || !boostFactors.keySet().equals(GameRules.SYMBOLS)
                || boostFactors.values().stream().anyMatch(value -> value == null || value < 1)
                || redisHost == null || redisHost.isBlank()
                || redisPort < 1 || redisPort > 65535 || redisDatabase < 0 || redisGameId < 1
                || redisConnectTimeoutMs < 1 || redisSocketTimeoutMs < 1) {
            throw new IllegalArgumentException("generator.properties 参数范围无效");
        }
    }

    public static EngineConfiguration load(Path path) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return from(properties);
    }

    public static EngineConfiguration from(Properties properties) {
        if (properties.stringPropertyNames().stream().anyMatch(key -> key.toLowerCase().contains("seed"))) {
            throw new IllegalArgumentException("正式配置禁止 seed");
        }
        Set<String> unsupported = new TreeSet<>();
        for (String key : properties.stringPropertyNames()) {
            if (!FIXED_KEYS.contains(key)
                    && !key.matches("weights\\.base\\.[0-9]+")
                    && !key.matches("weights\\.boost\\.[0-9]+")) {
                unsupported.add(key);
            }
        }
        if (!unsupported.isEmpty()) throw new IllegalArgumentException("正式配置包含未读取参数：" + unsupported);

        List<Integer> order = parseOrder(nonBlank(properties, "weights.order"));
        if (!order.equals(GameRules.SYMBOL_ORDER)) {
            throw new IllegalArgumentException("weights.order 必须固定为 " + join(GameRules.SYMBOL_ORDER));
        }
        Map<Integer, Integer> base = new LinkedHashMap<>();
        Map<Integer, Integer> boosts = new LinkedHashMap<>();
        for (int symbol : GameRules.SYMBOL_ORDER) {
            base.put(symbol, positiveInteger(properties, "weights.base." + symbol));
            boosts.put(symbol, positiveInteger(properties, "weights.boost." + symbol));
        }

        return new EngineConfiguration(
                new GenerationPolicy(base),
                positiveLong(properties, "generation.count"),
                positiveInteger(properties, "generation.batch-size"),
                positiveInteger(properties, "retention.normal-per-multiplier"),
                positiveInteger(properties, "retention.mary-per-multiplier"),
                nonNegativeInteger(properties, "generation.normal-min-win-multiplier"),
                nonNegativeInteger(properties, "generation.normal-max-win-multiplier"),
                nonNegativeInteger(properties, "generation.mary-min-win-multiplier"),
                nonNegativeInteger(properties, "generation.mary-max-win-multiplier"),
                positiveInteger(properties, "generation.scene.normal-weight"),
                positiveInteger(properties, "generation.scene.free-expanding-wild-weight"),
                positiveInteger(properties, "generation.scene.hold-and-spins-weight"),
                positiveInteger(properties, "generation.hold.empty-weight"),
                positiveInteger(properties, "generation.hold.coin-weight"),
                nonBlank(properties, "weights.source"), order, boosts,
                nonBlank(properties, "redis.host"), positiveInteger(properties, "redis.port"),
                required(properties, "redis.username"), required(properties, "redis.password"),
                nonNegativeInteger(properties, "redis.database"), bool(properties, "redis.ssl"),
                positiveInteger(properties, "redis.connect-timeout-ms"),
                positiveInteger(properties, "redis.socket-timeout-ms"),
                positiveInteger(properties, "redis.game-id"));
    }

    /** phase=0 为基础权重；phase=1..N 只放大 order 中对应的一张牌。 */
    public Map<Integer, Integer> effectiveWeights(int phase) {
        if (phase < 0 || phase > weightOrder.size()) throw new IllegalArgumentException("非法权重相位：" + phase);
        Map<Integer, Integer> effective = new LinkedHashMap<>(generationPolicy.symbolWeights());
        if (phase > 0) {
            int symbol = weightOrder.get(phase - 1);
            effective.put(symbol, Math.multiplyExact(effective.get(symbol), boostFactors.get(symbol)));
        }
        return Map.copyOf(effective);
    }

    public int cycleLength() { return weightOrder.size() + 1; }

    /** 先选独立玩法场景，再由该场景自然生成事实；这不是目标奖金分类。 */
    public GenerationScene chooseScene(RandomSource random) {
        int total = Math.addExact(normalSceneWeight,
                Math.addExact(freeExpandingWildSceneWeight, holdAndSpinsSceneWeight));
        int draw = random.nextInt(total);
        if (draw < normalSceneWeight) return GenerationScene.NORMAL_PAID;
        draw -= normalSceneWeight;
        if (draw < freeExpandingWildSceneWeight) return GenerationScene.FREE_EXPANDING_WILD;
        return GenerationScene.HOLD_AND_SPINS;
    }

    private static List<Integer> parseOrder(String raw) {
        try {
            return java.util.Arrays.stream(raw.split(",", -1)).map(String::trim)
                    .map(Integer::parseInt).toList();
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("weights.order 格式必须为逗号分隔符号 ID", failure);
        }
    }

    private static String join(List<Integer> values) {
        return values.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null) throw new IllegalArgumentException("缺少参数：" + key);
        return value.trim();
    }

    private static String nonBlank(Properties properties, String key) {
        String value = required(properties, key);
        if (value.isBlank()) throw new IllegalArgumentException("参数不能为空：" + key);
        return value;
    }

    private static int positiveInteger(Properties properties, String key) {
        int value = integer(properties, key);
        if (value < 1) throw new IllegalArgumentException(key + " 必须为正整数");
        return value;
    }

    private static int nonNegativeInteger(Properties properties, String key) {
        int value = integer(properties, key);
        if (value < 0) throw new IllegalArgumentException(key + " 不能为负数");
        return value;
    }

    private static int integer(Properties properties, String key) {
        try { return Integer.parseInt(required(properties, key)); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException(key + " 必须为整数", failure); }
    }

    private static long positiveLong(Properties properties, String key) {
        try {
            long value = Long.parseLong(required(properties, key));
            if (value < 1) throw new IllegalArgumentException(key + " 必须为正整数");
            return value;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(key + " 必须为正整数", failure);
        }
    }

    private static boolean bool(Properties properties, String key) {
        String value = required(properties, key);
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException(key + " 必须为 true 或 false");
        }
        return Boolean.parseBoolean(value);
    }
}
