package com.cpgame.christmasgift.api;

import com.cpgame.christmasgift.core.RedisClient;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

record ServerConfig(Path configPath, Path publishDirectory, Path stateFile, String redisHost, int redisPort,
                    String redisUsername, String redisPassword, int redisDatabase, boolean redisSsl,
                    int connectTimeoutMs, int socketTimeoutMs, int gameId, double featureProbability,
                    double ordinaryLossProbability) {
    static ServerConfig load(Path configPath, Path publishOverride) throws Exception {
        Properties p = new Properties();
        try (InputStream input = Files.newInputStream(configPath)) { p.load(input); }
        Path base = configPath.toAbsolutePath().normalize().getParent();
        Path publish = publishOverride != null ? publishOverride.toAbsolutePath().normalize()
            : base.resolve(required(p,"publish.directory")).normalize();
        Path state = base.resolve(required(p,"state.file")).normalize();
        ServerConfig config = new ServerConfig(configPath.toAbsolutePath().normalize(), publish, state,
            required(p,"redis.host"), integer(p,"redis.port"), p.getProperty("redis.username",""), p.getProperty("redis.password",""),
            integer(p,"redis.database"), Boolean.parseBoolean(required(p,"redis.ssl")), integer(p,"redis.connect-timeout-ms"),
            integer(p,"redis.socket-timeout-ms"), integer(p,"redis.game-id"), decimal(p,"selection.feature-probability"),
            decimal(p,"selection.ordinary-loss-probability"));
        if (!"18.234.101.161".equals(config.redisHost) || config.redisPort != 8021 || config.redisDatabase < 0 || config.gameId <= 0) {
            throw new IllegalArgumentException("fixed Redis/game contract mismatch");
        }
        if (!Files.isRegularFile(config.publishDirectory.resolve("index.html"))) throw new IllegalArgumentException("publish/index.html missing");
        return config;
    }
    RedisClient openRedis() throws Exception {
        return new RedisClient(redisHost, redisPort, redisUsername, redisPassword, redisDatabase, redisSsl, connectTimeoutMs, socketTimeoutMs);
    }
    private static String required(Properties p,String key) { String value=p.getProperty(key); if(value==null||value.isBlank())throw new IllegalArgumentException("missing "+key); return value.trim(); }
    private static int integer(Properties p,String key) { return Integer.parseInt(required(p,key)); }
    private static double decimal(Properties p,String key) { return Double.parseDouble(required(p,key)); }
}
