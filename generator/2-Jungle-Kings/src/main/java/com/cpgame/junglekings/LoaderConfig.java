package com.cpgame.junglekings;

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

/** Formal Loader configuration. Seed and multiplier-target settings are rejected. */
public record LoaderConfig(
        String redisHost, int redisPort, String redisPassword, int redisDatabase,
        int connectTimeoutMillis, int readTimeoutMillis,
        int totalMembers, int maximumMembersPerMultiplier, int maximumCandidates,
        BigDecimal betSize, int betLevel, List<RoundMode> modes) {

    public static LoaderConfig load(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) { properties.load(input); }
        for (String name : properties.stringPropertyNames()) {
            String normalized = name.toLowerCase(Locale.ROOT);
            if (normalized.contains("seed") || normalized.contains("min-mul") || normalized.contains("max-mul")) {
                throw new IllegalArgumentException("formal Loader config forbids seed/multiplier pursuit: " + name);
            }
        }
        int rawGameId = integer(properties, "game.raw-id", GameRuleCore.RAW_GAME_ID);
        int redisGameId = integer(properties, "redis.game-id", GameRuleCore.RAW_GAME_ID);
        if (rawGameId != GameRuleCore.RAW_GAME_ID || redisGameId != 8000002) {
            throw new IllegalArgumentException("sourceGameId and redisGameId must both be raw gid 2");
        }
        BigDecimal betSize = decimal(properties, "generation.bet-size", new BigDecimal("0.5"));
        int betLevel = integer(properties, "generation.bet-level", 1);
        GameRuleCore.validateBet(betSize, betLevel);
        List<RoundMode> modes = parseModes(properties.getProperty("generation.modes", "LOSS,WIN"));
        LoaderConfig config = new LoaderConfig(
                properties.getProperty("redis.host", "192.168.10.3").strip(),
                integer(properties, "redis.port", 6379),
                properties.getProperty("redis.password", ""),
                integer(properties, "redis.database", 15),
                integer(properties, "redis.connect-timeout-ms", 3000),
                integer(properties, "redis.read-timeout-ms", 10000),
                integer(properties, "generation.total-members", 600),
                integer(properties, "generation.max-members-per-multiplier", 300),
                integer(properties, "generation.max-candidates", 200000),
                betSize, betLevel, modes);
        config.validate();
        return config;
    }

    private void validate() {
        if (redisHost.isBlank() || redisPort < 1 || redisDatabase < 0
                || connectTimeoutMillis < 1 || readTimeoutMillis < 1 || totalMembers < 1
                || maximumMembersPerMultiplier < 1 || maximumCandidates < totalMembers
                || modes.isEmpty()
                || betSize.compareTo(new BigDecimal("0.5")) != 0 || betLevel != 1) {
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
