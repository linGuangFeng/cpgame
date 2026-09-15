package com.cpgame.batcha.g8;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/** Strict formal Loader configuration. Seed and multiplier-target settings are rejected. */
public record LoaderConfig(
        String redisHost, int redisPort, String redisPassword, int redisDatabase,
        int connectTimeoutMillis, int readTimeoutMillis,
        int totalMembers, int maximumMembersPerMultiplier, int maximumCandidates,
        BigDecimal maximumRoundMultiplier, int maximumSteps,
        BigDecimal betSize, int betLevel, List<RoundMode> modes, LoaderLimits outputLimits) {
        public LoaderConfig(
        String redisHost, int redisPort, String redisPassword, int redisDatabase,
        int connectTimeoutMillis, int readTimeoutMillis,
        int totalMembers, int maximumMembersPerMultiplier, int maximumCandidates,
        BigDecimal maximumRoundMultiplier, int maximumSteps,
        BigDecimal betSize, int betLevel, List<RoundMode> modes) { this(redisHost, redisPort, redisPassword, redisDatabase, connectTimeoutMillis, readTimeoutMillis, totalMembers, maximumMembersPerMultiplier, maximumCandidates, maximumRoundMultiplier, maximumSteps, betSize, betLevel, modes, new LoaderLimits(new java.util.Properties())); }


    public static LoaderConfig load(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) { properties.load(input); LoaderLimits.checkKeys(properties); }
        for (String name : properties.stringPropertyNames()) {
            String normalized = name.toLowerCase(Locale.ROOT);
            if (normalized.contains("seed") || normalized.contains("min-mul")
                || (normalized.contains("max-mul") && !normalized.equals("generation.maximum-round-multiplier"))) {
                throw new IllegalArgumentException("formal Loader config forbids seed/multiplier pursuit: " + name);
            }
        }
        int rawGameId = integer(properties, "game.raw-id", GameRuleCore.RAW_GAME_ID);
        int redisGameId = integer(properties, "redis.game-id", 8000008);
        if (rawGameId != GameRuleCore.RAW_GAME_ID || redisGameId != 8000008) {
            throw new IllegalArgumentException("raw game ID must be 8 and redis.game-id must be 8000008");
        }
        BigDecimal betSize = decimal(properties, "generation.bet-size", new BigDecimal("0.05"));
        int betLevel = integer(properties, "generation.bet-level", 4);
        GameRuleCore.validateBet(betSize, betLevel);
        List<RoundMode> modes = parseModes(properties.getProperty("generation.modes", "LOSS,DRAGON"));
        LoaderConfig config = new LoaderConfig(
            properties.getProperty("redis.host", "192.168.10.3").strip(),
            integer(properties, "redis.port", 6379),
            properties.getProperty("redis.password", ""),
            integer(properties, "redis.database", 15),
            integer(properties, "redis.connect-timeout-ms", 3000),
            integer(properties, "redis.socket-timeout-ms", integer(properties, "redis.read-timeout-ms", 30000)),
            integer(properties, "generation.total-members", 400),
            integer(properties, "generation.max-members-per-multiplier", 500),
            integer(properties, "generation.max-candidates", 80000),
            decimal(properties, "generation.maximum-round-multiplier", new BigDecimal("20000")),
            integer(properties, "generation.maximum-steps", GameRuleCore.MAX_STEPS_OBSERVED),
            betSize, betLevel, modes, new LoaderLimits(properties));
        config.validate();
        return config;
    }

    private void validate() {
        if (redisHost.isBlank() || redisPort < 1 || redisPort > 65535 || redisDatabase < 0
            || connectTimeoutMillis < 1 || readTimeoutMillis < 1 || totalMembers < 1
            || maximumMembersPerMultiplier < 1 || maximumCandidates < totalMembers
            || maximumRoundMultiplier.signum() <= 0 || maximumSteps < 1
            || maximumSteps > GameRuleCore.MAX_STEPS_OBSERVED || modes.isEmpty()
            || betSize.compareTo(new BigDecimal("0.05")) != 0) {
            throw new IllegalArgumentException("invalid formal Loader configuration");
        }
    }

    private static int integer(Properties properties, String key, int fallback) {
        return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)).strip());
    }

    private static BigDecimal decimal(Properties properties, String key, BigDecimal fallback) {
        return new BigDecimal(properties.getProperty(key, fallback.toPlainString()).strip());
    }

    private static List<RoundMode> parseModes(String value) {
        Set<RoundMode> modes = new LinkedHashSet<>();
        Arrays.stream(value.split(",")).map(String::strip).filter(item -> !item.isEmpty())
            .map(item -> RoundMode.valueOf(item.toUpperCase(Locale.ROOT))).forEach(modes::add);
        return List.copyOf(modes);
    }
}
