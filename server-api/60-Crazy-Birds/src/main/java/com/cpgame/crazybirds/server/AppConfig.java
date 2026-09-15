package com.cpgame.crazybirds.server;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record AppConfig(String address, int port, BigDecimal initialBalance, Path publishDirectory,
                        String redisHost, int redisPort, String redisUsername, String redisPassword,
                        int redisDatabase, int redisConnectTimeoutMs, int redisSocketTimeoutMs,
                        long redisGameId, int lossWeight, int winWeight, int specialWeight) {
    public static AppConfig load(Path file, int port, Path publishOverride) throws Exception {
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(reader); }
        for (String key : p.stringPropertyNames()) {
            if (key.toLowerCase().contains("seed")) throw new IllegalArgumentException("Controller 配置禁止 seed");
        }
        if (port < 50000 || port > 59999) throw new IllegalArgumentException("server.port 必须在 50000-59999");
        String address = p.getProperty("server.address", "0.0.0.0").trim();
        if (address.equals("localhost") || address.startsWith("127.")) {
            throw new IllegalArgumentException("server.address 必须监听所有网卡");
        }
        BigDecimal balance = new BigDecimal(p.getProperty("player.initial-balance", "10000.00"));
        Path publish = publishOverride != null ? publishOverride : file.toAbsolutePath().getParent().resolve(
                p.getProperty("publish.directory", "../../../publish/60-Crazy-Birds")).normalize();
        if (!Files.isRegularFile(publish.resolve("index.html"))) {
            throw new IllegalArgumentException("publish.directory 缺少 index.html: " + publish);
        }
        int loss = integer(p, "round.loss-weight", 0, 1_000_000);
        int win = integer(p, "round.win-weight", 0, 1_000_000);
        int special = integer(p, "round.special-weight", 0, 1_000_000);
        if ((long) loss + win + special <= 0) throw new IllegalArgumentException("结果权重不能全为0");
        return new AppConfig(address, port, balance, publish,
                required(p, "redis.host"), integer(p, "redis.port", 1, 65535),
                p.getProperty("redis.username", "").trim(), p.getProperty("redis.password", ""),
                integer(p, "redis.database", 0, Integer.MAX_VALUE),
                integer(p, "redis.connect-timeout-ms", 1, Integer.MAX_VALUE),
                integer(p, "redis.socket-timeout-ms", 1, Integer.MAX_VALUE),
                Long.parseLong(required(p, "redis.game-id")), loss, win, special);
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少配置: " + key);
        return value.trim();
    }

    private static int integer(Properties p, String key, int min, int max) {
        int value = Integer.parseInt(required(p, key));
        if (value < min || value > max) throw new IllegalArgumentException(key + " 超出范围");
        return value;
    }
}
