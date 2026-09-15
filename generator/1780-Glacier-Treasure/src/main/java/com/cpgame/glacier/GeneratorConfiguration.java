package com.cpgame.glacier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Formal loader configuration. Unknown keys are rejected. Every declared key is read.
 * No seed, no write-disable switch, no multiplier chase.
 */
public final class GeneratorConfiguration {
    public final LoaderLimits outputLimits;
    static final int MAX_GENERATION_TARGET = Integer.MAX_VALUE;
    static final String[] STRUCTURES = {
        "1N-1N-3N", "1N-1N-1N-2N", "1N-4N", "1N-1N-1N-1N-1N", "1N-1N-2N-1N",
        "1N-3N-1N", "1N-2N-2N", "1N-1N-3S", "1N-2N-1N-1N", "3N-2N",
        "1N-4S", "2N-3N", "1N-1N-1N-2S", "1N-3S-1N", "4N-1N",
        "2N-1N-2N", "1N-1N-2S-1N", "3N-1N-1N", "1N-2S-2N", "1N-2N-2S",
        "2N-2N-1N", "2N-1N-1N-1N", "1N-2S-1N-1N", "2N-3S", "3S-2N",
        "2S-3N", "2N-1N-2S", "3S-1N-1N", "2S-1N-2N", "3N-2S",
        "2S-1N-1N-1N", "4S-1N", "2N-2S-1N", "2S-2N-1N", "1N-2S-2S",
        "2S-1N-2S", "2S-2S-1N", "3S-2S", "2S-3S"
    };
    private static final String[] MODES = {
        "paid-initial", "paid-refill", "free-initial", "free-refill"
    };
    private static final Set<String> FIXED_KEYS = Set.of(
        "redis.host", "redis.port", "redis.username", "redis.password", "redis.database", "redis.ssl",
        "redis.connect-timeout-ms", "redis.socket-timeout-ms", "redis.game-id",
        "generation.loss-count", "generation.win-count", "generation.special-count",
        "generation.batch-size", "generation.max-members-per-multiplier",
        "generation.special-max-members-per-multiplier",
        "generation.max-consecutive-wins", "generation.max-mary-spins",
        "generation.normal-min-win-multiplier", "generation.normal-max-win-multiplier",
        "generation.mary-min-win-multiplier", "generation.mary-max-win-multiplier",
        "generation.entry-switch-every", "generation.special-scatter-boost",
        "generation.loss.constructive-attempts", "generation.loss.fallback-samples",
        "generation.cap.scatter-units-board", "generation.cap.scatter-units-reel",
        "generation.cap.scatter-symbols-column", "generation.cap.scatter-symbols-free",
        "generation.cap.wild-board", "generation.cap.wild-reel", "generation.cap.cascade-pages"
    );

    final String redisHost, redisUsername, redisPassword;
    final int redisPort, redisDatabase, connectTimeoutMs, socketTimeoutMs;
    final boolean redisSsl;
    final long redisGameId;
    final int lossCount, winCount, specialCount, batchSize, maxMembersPerMultiplier;
    final int maxConsecutiveWins, maxMarySpins;
    final int normalMinWinMultiplier, normalMaxWinMultiplier, maryMinWinMultiplier, maryMaxWinMultiplier;
    final int entrySwitchEvery, specialScatterBoost, lossConstructiveAttempts, lossFallbackSamples;
    final int maxScatterUnitsBoard, maxScatterUnitsReel, maxScatterSymbolsColumn, maxScatterSymbolsFree;
    final int maxWildBoard, maxWildReel, maxCascadePages;
    final int[] paidInitial, paidRefill, freeInitial, freeRefill, silverToGold;
    final Map<String, Integer> paidInitialStructures;

    private GeneratorConfiguration(Properties p) {
        outputLimits = new LoaderLimits(p);
        rejectUnknown(p);
        redisHost = required(p, "redis.host");
        redisPort = integer(p, "redis.port", 1, 65535);
        redisUsername = p.getProperty("redis.username", "").trim();
        redisPassword = p.getProperty("redis.password", "");
        redisDatabase = integer(p, "redis.database", 0, Integer.MAX_VALUE);
        redisSsl = bool(p, "redis.ssl");
        connectTimeoutMs = integer(p, "redis.connect-timeout-ms", 1, 300_000);
        socketTimeoutMs = integer(p, "redis.socket-timeout-ms", 1, 3_600_000);
        redisGameId = longValue(p, "redis.game-id", 1, Long.MAX_VALUE);
        lossCount = integer(p, "generation.loss-count", 0, MAX_GENERATION_TARGET);
        winCount = integer(p, "generation.win-count", 0, MAX_GENERATION_TARGET);
        specialCount = integer(p, "generation.special-count", 0, MAX_GENERATION_TARGET);
        if ((long) lossCount + winCount + specialCount <= 0)
            throw new IllegalArgumentException("loss/win/special generation targets cannot all be 0");
        batchSize = integer(p, "generation.batch-size", 1, 10_000);
        maxMembersPerMultiplier = integer(p, "generation.max-members-per-multiplier", 1, 1_000_000);
        maxConsecutiveWins = integer(p, "generation.max-consecutive-wins", 1, 100);
        maxMarySpins = integer(p, "generation.max-mary-spins", 1, 10_000);
        normalMinWinMultiplier = integer(p, "generation.normal-min-win-multiplier", 0, Integer.MAX_VALUE);
        normalMaxWinMultiplier = integer(p, "generation.normal-max-win-multiplier", 1, Integer.MAX_VALUE);
        maryMinWinMultiplier = integer(p, "generation.mary-min-win-multiplier", 0, Integer.MAX_VALUE);
        maryMaxWinMultiplier = integer(p, "generation.mary-max-win-multiplier", 1, Integer.MAX_VALUE);
        if (normalMinWinMultiplier > normalMaxWinMultiplier || maryMinWinMultiplier > maryMaxWinMultiplier)
            throw new IllegalArgumentException("min multiplier cannot exceed max");
        entrySwitchEvery = integer(p, "generation.entry-switch-every", 1, 1_000_000);
        specialScatterBoost = integer(p, "generation.special-scatter-boost", 1, 1000);
        lossConstructiveAttempts = integer(p, "generation.loss.constructive-attempts", 1, 10_000);
        lossFallbackSamples = integer(p, "generation.loss.fallback-samples", 1, 10_000);
        maxScatterUnitsBoard = integer(p, "generation.cap.scatter-units-board", 1, 20);
        maxScatterUnitsReel = integer(p, "generation.cap.scatter-units-reel", 1, 10);
        maxScatterSymbolsColumn = integer(p, "generation.cap.scatter-symbols-column", 1, 5);
        maxScatterSymbolsFree = integer(p, "generation.cap.scatter-symbols-free", 1, 10);
        maxWildBoard = integer(p, "generation.cap.wild-board", 0, 20);
        maxWildReel = integer(p, "generation.cap.wild-reel", 0, 10);
        maxCascadePages = integer(p, "generation.cap.cascade-pages", 1, 50);
        paidInitial = weights(p, "paid-initial");
        paidRefill = weights(p, "paid-refill");
        freeInitial = weights(p, "free-initial");
        freeRefill = weights(p, "free-refill");
        silverToGold = silverWeights(p);
        paidInitialStructures = structures(p);
    }

    public static GeneratorConfiguration load(Path path) {
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(path.toAbsolutePath().normalize(), StandardCharsets.UTF_8)) {
            p.load(reader); LoaderLimits.checkKeys(p);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read generator.properties: " + path, e);
        }
        return new GeneratorConfiguration(p);
    }

    GenerationModel model() {
        return new GenerationModel(this);
    }

    private static int[] weights(Properties p, String mode) {
        int[] out = new int[13];
        long total = 0;
        for (int i = 1; i <= 13; i++) {
            out[i - 1] = integer(p, "generation.symbol." + i + "." + mode + "-weight", 0, Integer.MAX_VALUE);
            total += out[i - 1];
        }
        if (total <= 0) throw new IllegalArgumentException(mode + " symbol weight total must be > 0");
        return out;
    }

    private static int[] silverWeights(Properties p) {
        int[] out = new int[11];
        long total = 0;
        for (int i = 1; i <= 11; i++) {
            out[i - 1] = integer(p, "generation.silver-to-gold." + i + "-weight", 0, Integer.MAX_VALUE);
            total += out[i - 1];
        }
        if (total <= 0) throw new IllegalArgumentException("silver-to-gold weight total must be > 0");
        return out;
    }

    private static Map<String, Integer> structures(Properties p) {
        var out = new LinkedHashMap<String, Integer>();
        long total = 0;
        for (String sig : STRUCTURES) {
            int n = integer(p, "generation.structure.paid-initial." + sig, 0, Integer.MAX_VALUE);
            out.put(sig, n);
            total += n;
        }
        if (total <= 0) throw new IllegalArgumentException("paid-initial structure weight total must be > 0");
        return Map.copyOf(out);
    }

    private static void rejectUnknown(Properties p) {
        var allowed = new HashSet<>(FIXED_KEYS);
        for (String mode : MODES)
            for (int i = 1; i <= 13; i++) allowed.add("generation.symbol." + i + "." + mode + "-weight");
        for (int i = 1; i <= 11; i++) allowed.add("generation.silver-to-gold." + i + "-weight");
        for (String sig : STRUCTURES) allowed.add("generation.structure.paid-initial." + sig);
        for (String key : p.stringPropertyNames())
            if (!allowed.contains(key)) throw new IllegalArgumentException("unknown generator.properties key: " + key);
    }

    private static String required(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("missing " + key);
        return v.trim();
    }

    private static int integer(Properties p, String key, int min, int max) {
        int v = Integer.parseInt(required(p, key));
        if (v < min || v > max) throw new IllegalArgumentException(key + " out of range");
        return v;
    }

    private static long longValue(Properties p, String key, long min, long max) {
        long v = Long.parseLong(required(p, key));
        if (v < min || v > max) throw new IllegalArgumentException(key + " out of range");
        return v;
    }

    private static boolean bool(Properties p, String key) {
        String v = required(p, key);
        if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false"))
            throw new IllegalArgumentException(key + " must be true/false");
        return Boolean.parseBoolean(v);
    }
}
