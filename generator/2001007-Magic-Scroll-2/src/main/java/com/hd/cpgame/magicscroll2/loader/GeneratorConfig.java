package com.hd.cpgame.magicscroll2.loader;

import com.hd.cpgame.magicscroll2.core.GameConstants;
import com.hd.cpgame.magicscroll2.core.GenerationPolicy;
import com.hd.cpgame.magicscroll2.core.SymbolWeightPolicy;
import com.hd.cpgame.magicscroll2.core.TrialProbabilityPolicy;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/** Formal Redis Loader config. No seed, no scenario forcing, no JSONL. */
final class GeneratorConfig {
    final LoaderLimits outputLimits;
    static final int MAX_GENERATION_TARGET = Integer.MAX_VALUE;
    final String host, username, password;
    final int port, database, connectTimeoutMs, socketTimeoutMs;
    final boolean ssl;
    final long redisGameId;
    final int lossCount, winCount, specialCount, batchSize, maxMembersPerMultiplier;
    final BigDecimal paidBet, normalMaxWinMultiplier, specialMaxWinMultiplier;
    final GenerationPolicy generationPolicy;
    final TrialProbabilityPolicy modeWeights;
    final SymbolWeightPolicy symbolWeights;

    private GeneratorConfig(Properties p) {
        outputLimits = new LoaderLimits(p);
        rejectForbiddenAndUnknown(p);
        host = required(p, "redis.host");
        port = integer(p, "redis.port");
        username = p.getProperty("redis.username", "").trim();
        password = p.getProperty("redis.password", "");
        database = integer(p, "redis.database");
        ssl = bool(p, "redis.ssl");
        connectTimeoutMs = integer(p, "redis.connect-timeout-ms");
        socketTimeoutMs = integer(p, "redis.socket-timeout-ms");
        redisGameId = longValue(p, "redis.game-id");
        lossCount = integer(p, "generation.loss-count");
        winCount = integer(p, "generation.win-count");
        specialCount = integer(p, "generation.special-count");
        batchSize = integer(p, "generation.batch-size");
        maxMembersPerMultiplier = integer(p, "generation.max-members-per-multiplier");
        paidBet = decimal(p, "paid.bet");
        normalMaxWinMultiplier = decimal(p, "generation.normal-max-win-multiplier");
        specialMaxWinMultiplier = decimal(p, "generation.special-max-win-multiplier");
        generationPolicy = new GenerationPolicy(integer(p, "generation.loss.constructive-attempts"),
                integer(p, "generation.loss.fallback-samples"),
                GenerationPolicy.DEFAULT_VALIDATION_SAMPLES,
                GenerationPolicy.DEFAULT_MINIMUM_FIRST_SUCCESS_PERCENT,
                integer(p, "generation.max-round-steps"),
                integer(p, "generation.max-consecutive-wins"),
                normalMaxWinMultiplier.max(specialMaxWinMultiplier).intValueExact(), maxMembersPerMultiplier);
        modeWeights = new TrialProbabilityPolicy(1, 1, 1, 1);
        int[] weights = new int[10];
        for (int symbol = 3; symbol <= 12; symbol++) {
            weights[symbol - 3] = integer(p, "generation.symbol." + symbol + "-weight");
        }
        symbolWeights = new SymbolWeightPolicy(weights,
                integer(p, "generation.material.empty-weight"),
                integer(p, "generation.material.dirt-weight"));
        validate();
    }

    static GeneratorConfig load(File file) throws Exception {
        File canonical = file.getCanonicalFile();
        if (!canonical.isFile()) throw new IllegalArgumentException("配置文件不存在: " + canonical);
        Properties p = new Properties();
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(canonical), StandardCharsets.UTF_8)) {
            p.load(reader); LoaderLimits.checkKeys(p);
        }
        return new GeneratorConfig(p);
    }

    private void validate() {
        if (port < 1 || port > 65535 || database < 0 || connectTimeoutMs < 1 || socketTimeoutMs < 1
                || redisGameId < 1 || lossCount < 0 || winCount < 0 || specialCount < 0
                || lossCount > MAX_GENERATION_TARGET || winCount > MAX_GENERATION_TARGET
                || specialCount > MAX_GENERATION_TARGET
                || batchSize < 1 || batchSize > 10_000 || maxMembersPerMultiplier < 1
                || paidBet.compareTo(GameConstants.MINIMUM_TOTAL_BET) < 0
                || normalMaxWinMultiplier.signum() <= 0 || specialMaxWinMultiplier.signum() <= 0) {
            throw new IllegalArgumentException("generator.properties 参数超出允许范围");
        }
    }

    private static void rejectForbiddenAndUnknown(Properties p) {
        Set<String> allowed = new HashSet<String>(Arrays.asList(
                "redis.host", "redis.port", "redis.username", "redis.password", "redis.database", "redis.ssl",
                "redis.connect-timeout-ms", "redis.socket-timeout-ms", "redis.game-id", "paid.bet",
                "generation.loss-count", "generation.win-count", "generation.special-count",
                "generation.batch-size", "generation.max-members-per-multiplier",
                "generation.special-max-members-per-multiplier",
                "generation.max-consecutive-wins", "generation.max-round-steps",
                "generation.normal-min-win-multiplier", "generation.normal-max-win-multiplier",
                "generation.special-min-win-multiplier", "generation.special-max-win-multiplier",
                "generation.loss.constructive-attempts", "generation.loss.fallback-samples",
                "generation.symbol.3-weight", "generation.symbol.4-weight", "generation.symbol.5-weight",
                "generation.symbol.6-weight", "generation.symbol.7-weight", "generation.symbol.8-weight",
                "generation.symbol.9-weight", "generation.symbol.10-weight", "generation.symbol.11-weight",
                "generation.symbol.12-weight", "generation.material.empty-weight",
                "generation.material.dirt-weight"));
        for (String key : p.stringPropertyNames()) {
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.contains("seed") || lower.contains("scenario") || lower.contains("jsonl")
                    || lower.contains("demo-script") || lower.contains("demoscript")
                    || lower.equals("redis.enabled") || lower.equals("write-enabled")
                    || lower.equals("write.enabled") || lower.equals("local_jsonl")
                    || lower.equals("output.file")) {
                throw new IllegalArgumentException("正式 Loader 禁止配置: " + key);
            }
            if (!allowed.contains(key)) throw new IllegalArgumentException("代码未读取的配置项: " + key);
        }
        for (String key : allowed) if (!p.containsKey(key)) throw new IllegalArgumentException("缺少配置项: " + key);
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("缺少配置项: " + key);
        return value.trim();
    }
    private static int integer(Properties p, String key) {
        try { return Integer.parseInt(required(p, key)); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(key + " 必须为整数", ex); }
    }
    private static long longValue(Properties p, String key) {
        try { return Long.parseLong(required(p, key)); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(key + " 必须为整数", ex); }
    }
    private static BigDecimal decimal(Properties p, String key) {
        try { return new BigDecimal(required(p, key)); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(key + " 必须为数字", ex); }
    }
    private static boolean bool(Properties p, String key) {
        String value = required(p, key);
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(key + " 必须为 true 或 false");
        }
        return Boolean.parseBoolean(value);
    }
}
