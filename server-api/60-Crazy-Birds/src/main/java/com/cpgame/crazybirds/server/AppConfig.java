package com.cpgame.crazybirds.server;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.random.RandomGenerator;

public record AppConfig(String address, int port, BigDecimal initialBalance, Path publishDirectory,
                        String redisHost, int redisPort, String redisUsername, String redisPassword,
                        int redisDatabase, boolean redisSsl, int redisConnectTimeoutMs, int redisSocketTimeoutMs,
                        long redisGameId, int normalSceneWeight, int freeSpinsSceneWeight,
                        BandWeights normalBands, BandWeights freeSpinsBands) {
    public static AppConfig load(Path file, int port, Path publishOverride) throws Exception {
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(reader); }
        rejectUnknown(p);
        if (port < 50000 || port > 59999) throw new IllegalArgumentException("server.port 必须在 50000-59999");
        String address = required(p, "server.address");
        if (address.equals("localhost") || address.startsWith("127.")) {
            throw new IllegalArgumentException("server.address 必须监听所有网卡");
        }
        BigDecimal balance = new BigDecimal(required(p, "player.initial-balance"));
        Path publish = publishOverride != null ? publishOverride : file.toAbsolutePath().getParent().resolve(
                required(p, "publish.directory")).normalize();
        if (!Files.isRegularFile(publish.resolve("index.html"))) {
            throw new IllegalArgumentException("publish.directory 缺少 index.html: " + publish);
        }
        int normalScene = integer(p, "selection.normal.weight", 0, 1_000_000);
        int freeScene = integer(p, "selection.free-spins.weight", 0, 1_000_000);
        if ((long) normalScene + freeScene <= 0) throw new IllegalArgumentException("场景权重不能全为0");
        return new AppConfig(address, port, balance, publish,
                required(p, "redis.host"), integer(p, "redis.port", 1, 65_535),
                p.getProperty("redis.username", "").trim(), p.getProperty("redis.password", ""),
                integer(p, "redis.database", 0, Integer.MAX_VALUE), bool(p, "redis.ssl"),
                integer(p, "redis.connect-timeout-ms", 1, Integer.MAX_VALUE),
                integer(p, "redis.socket-timeout-ms", 1, Integer.MAX_VALUE),
                longValue(p, "redis.game-id", 1, 99_999_999L), normalScene, freeScene,
                BandWeights.load(p, "selection.normal.multiplier."),
                BandWeights.load(p, "selection.free-spins.multiplier."));
    }

    public boolean chooseFreeSpins(RandomGenerator random) {
        int total = Math.addExact(normalSceneWeight, freeSpinsSceneWeight);
        return random.nextInt(total) >= normalSceneWeight;
    }

    private static void rejectUnknown(Properties p) {
        Set<String> allowed = new HashSet<>(Set.of(
                "server.address", "player.initial-balance", "publish.directory",
                "redis.host", "redis.port", "redis.database", "redis.username", "redis.password", "redis.ssl",
                "redis.connect-timeout-ms", "redis.socket-timeout-ms", "redis.game-id",
                "selection.normal.weight", "selection.free-spins.weight"));
        for (String prefix : List.of("selection.normal.multiplier.", "selection.free-spins.multiplier.")) {
            for (MultiplierBand band : MultiplierBand.values()) allowed.add(prefix + band.propertySuffix());
        }
        for (String key : p.stringPropertyNames()) {
            if (key.toLowerCase().contains("seed")) throw new IllegalArgumentException("Controller 配置禁止 seed");
            if (!allowed.contains(key)) throw new IllegalArgumentException("Controller 未读取的配置项: " + key);
        }
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少配置: " + key);
        return value.trim();
    }

    private static int integer(Properties p, String key, int min, int max) {
        long value = longValue(p, key, min, max);
        return Math.toIntExact(value);
    }

    private static long longValue(Properties p, String key, long min, long max) {
        long value;
        try { value = Long.parseLong(required(p, key)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key + " 必须为整数", e); }
        if (value < min || value > max) throw new IllegalArgumentException(key + " 超出范围");
        return value;
    }

    private static boolean bool(Properties p, String key) {
        String value = required(p, key);
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(key + " 必须为 true 或 false");
        }
        return Boolean.parseBoolean(value);
    }

    public record BandWeights(List<Integer> weights) {
        public BandWeights { weights = List.copyOf(weights); }

        static BandWeights load(Properties p, String prefix) {
            List<Integer> values = new ArrayList<>();
            long total = 0;
            for (MultiplierBand band : MultiplierBand.values()) {
                int value = integer(p, prefix + band.propertySuffix(), 0, 1_000_000);
                values.add(value);
                total += value;
            }
            if (total <= 0) throw new IllegalArgumentException(prefix + " 六档权重不能全为0");
            return new BandWeights(values);
        }

        public MultiplierBand choose(RandomGenerator random) {
            int total = 0;
            for (int weight : weights) total = Math.addExact(total, weight);
            int point = random.nextInt(total);
            for (int i = 0; i < weights.size(); i++) {
                if (point < weights.get(i)) return MultiplierBand.values()[i];
                point -= weights.get(i);
            }
            throw new IllegalStateException("倍率档位抽样越界");
        }
    }
}
