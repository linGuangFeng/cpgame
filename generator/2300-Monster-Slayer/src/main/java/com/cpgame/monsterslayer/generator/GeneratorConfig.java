package com.cpgame.monsterslayer.generator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

public final class GeneratorConfig {
    public final LoaderLimits outputLimits;
    public final String redisHost, redisUsername, redisPassword;
    public final int redisPort, redisDatabase, redisGameId, connectTimeoutMs, socketTimeoutMs;
    public final boolean redisSsl;
    public final int lossCount, winCount, specialCount, buyCountPerMode, batchSize;
    public final int maxMembersPerMultiplier, specialMaxMembersPerMultiplier, maxCentiMultiplier, maxConsecutiveWins, maxMarySpins;
    public final int normalMinWinMultiplier, normalMaxWinMultiplier, maryMinWinMultiplier, maryMaxWinMultiplier;
    public final int specialTriggerWeightMultiplier;
    public final int[] normalWeights, maryWeights;

    private GeneratorConfig(Properties p) {
        outputLimits = new LoaderLimits(p);
        for (String key : p.stringPropertyNames()) {
            if (key.toLowerCase(Locale.ROOT).contains("seed")) {
                throw new IllegalArgumentException("seed properties are forbidden: " + key);
            }
        }
        redisHost = p.getProperty("redis.host", "192.168.10.3").trim();
        redisPort = integer(p, "redis.port", 6379);
        redisDatabase = integer(p, "redis.database", 15);
        redisGameId = integer(p, "redis.game-id", 2300);
        redisUsername = p.getProperty("redis.username", "").trim();
        redisPassword = p.getProperty("redis.password", "");
        redisSsl = Boolean.parseBoolean(p.getProperty("redis.ssl", "false"));
        connectTimeoutMs = integer(p, "redis.connect-timeout-ms", 5000);
        socketTimeoutMs = integer(p, "redis.socket-timeout-ms", 30000);
        lossCount = integer(p, "generation.loss-count", 300);
        winCount = integer(p, "generation.win-count", 300);
        specialCount = integer(p, "generation.special-count", 0);
        buyCountPerMode = integer(p, "generation.buy-count-per-mode", 80);
        batchSize = integer(p, "generation.batch-size", 50);
        maxMembersPerMultiplier = integer(p, "generation.max-members-per-multiplier", 300);
        specialMaxMembersPerMultiplier = integer(p, "generation.special-max-members-per-multiplier", 100);
        maxConsecutiveWins = integer(p, "generation.max-consecutive-wins", 10);
        maxMarySpins = integer(p, "generation.max-mary-spins", 32);
        normalMinWinMultiplier = integer(p, "generation.normal-min-win-multiplier", 1);
        normalMaxWinMultiplier = integer(p, "generation.normal-max-win-multiplier", 999999);
        maryMinWinMultiplier = integer(p, "generation.mary-min-win-multiplier", 1);
        maryMaxWinMultiplier = integer(p, "generation.mary-max-win-multiplier", 999999);
        maxCentiMultiplier = integer(p, "generation.max-centi-multiplier", normalMaxWinMultiplier);
        specialTriggerWeightMultiplier = integer(p, "generation.special-trigger-weight-multiplier",
                MonsterSlayerBoardGenerator.SPECIAL_TRIGGER_WEIGHT_MULTIPLIER);
        normalWeights = symbolWeights(p, "normal", MonsterSlayerBoardGenerator.defaultNormalWeights());
        maryWeights = symbolWeights(p, "mary", MonsterSlayerBoardGenerator.defaultMaryWeights());
        if (redisHost.isBlank() || redisPort < 1 || redisPort > 65535 || redisDatabase < 0
                || redisGameId < 1 || redisGameId > 99_999_999
                || connectTimeoutMs < 1 || socketTimeoutMs < 1) {
            throw new IllegalArgumentException("invalid Redis host, port, database, game ID (1..99999999), or timeouts");
        }
        if (lossCount < 0 || winCount < 0 || specialCount < 0 || buyCountPerMode < 0
                || batchSize < 1 || batchSize > 10_000
                || maxMembersPerMultiplier < 1 || specialMaxMembersPerMultiplier < 1 || maxCentiMultiplier < 0
                || maxConsecutiveWins < 1 || maxMarySpins < 1
                || normalMinWinMultiplier < 0 || maryMinWinMultiplier < 0
                || normalMaxWinMultiplier < 1 || maryMaxWinMultiplier < 1
                || normalMinWinMultiplier > normalMaxWinMultiplier
                || maryMinWinMultiplier > maryMaxWinMultiplier
                || specialTriggerWeightMultiplier < 1) {
            throw new IllegalArgumentException("invalid generation counts or limits");
        }
        if (specialCount > 0) {
            throw new IllegalArgumentException("generation.special-count=" + specialCount
                    + " requests natural special rounds, which are not implemented for 2300; "
                    + "use generation.special-count=0 for supported ordinary/purchase generation");
        }
        MonsterSlayerBoardGenerator.validatedWeights(normalWeights, "normal");
        MonsterSlayerBoardGenerator.validatedWeights(maryWeights, "Mary");
    }

    public static GeneratorConfig load(Path path) throws IOException {
        Properties p = new Properties();
        try (var r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { p.load(r); LoaderLimits.checkKeys(p); }
        return new GeneratorConfig(p);
    }

    private static int integer(Properties p, String key, int fallback) {
        return Integer.parseInt(p.getProperty(key, Integer.toString(fallback)).trim());
    }

    private static int[] symbolWeights(Properties p, String mode, int[] defaults) {
        int[] result = defaults.clone();
        for (int symbol = 1; symbol <= 10; symbol++) {
            result[symbol - 1] = integer(p, "generation.symbol." + symbol + "." + mode + "-weight", result[symbol - 1]);
        }
        result[10] = integer(p, "generation.symbol.100." + mode + "-weight", result[10]);
        return result;
    }
}
