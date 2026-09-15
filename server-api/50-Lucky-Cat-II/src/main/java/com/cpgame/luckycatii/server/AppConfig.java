package com.cpgame.luckycatii.server;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record AppConfig(int port, BigDecimal initialBalance, Path publishDirectory,
                        String redisHost, int redisPort, String redisUsername, String redisPassword,
                        int redisDatabase, int redisConnectTimeoutMs, int redisSocketTimeoutMs,
                        long redisGameId, int lossWeight, int winWeight, int specialWeight) {
    public static AppConfig load(Path file, int port) throws Exception {
        if (port < 50000 || port > 59999) throw new IllegalArgumentException("port must be 50000-59999");
        Properties p = new Properties();
        if (file != null && Files.isRegularFile(file)) {
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(reader); }
        }
        for (String key : p.stringPropertyNames()) {
            String lower = key.toLowerCase();
            if (lower.contains("seed")) throw new IllegalArgumentException("Controller 配置禁止 seed");
            if (key.equals("server.port") || key.equals("controller.api-port") || key.equals("port")) {
                throw new IllegalArgumentException("managed Controller forbids hard-coded port settings");
            }
        }
        Path publish = (file == null ? Path.of(".") : file.toAbsolutePath().getParent()).resolve(
                p.getProperty("publish.directory", "../../../publish/50-Lucky-Cat-II")).normalize();
        if (!Files.isRegularFile(publish.resolve("index.html")))
            throw new IllegalArgumentException("publish.directory 缺少 index.html: " + publish);
        int loss = integer(p, "round.loss-weight", 1);
        int win = integer(p, "round.win-weight", 1);
        int special = integer(p, "round.special-weight", 1);
        return new AppConfig(port, new BigDecimal(p.getProperty("player.initial-balance", "10000.00")), publish,
                required(p, "redis.host"), integer(p, "redis.port", 1),
                p.getProperty("redis.username", "").trim(), p.getProperty("redis.password", ""),
                integer(p, "redis.database", 0),
                integer(p, "redis.connect-timeout-ms", 1),
                integer(p, "redis.socket-timeout-ms", 1),
                Long.parseLong(required(p, "redis.game-id")), loss, win, special);
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少配置: " + key);
        return value.trim();
    }

    private static int integer(Properties p, String key, int min) {
        int value = Integer.parseInt(required(p, key));
        if (value < min) throw new IllegalArgumentException(key + " 超出范围");
        return value;
    }
}
