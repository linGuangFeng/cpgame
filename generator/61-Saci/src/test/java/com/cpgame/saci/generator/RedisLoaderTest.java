package com.cpgame.saci.generator;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisLoaderTest {
    @Test void rejectsSeedAndUnknownKeys() throws Exception {
        Path file = Files.createTempFile("saci-gen", ".properties");
        Files.writeString(file, "redis.host=127.0.0.1\nseed=1\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> GeneratorConfig.load(file));
    }

    @Test void writesAsciiMembersToIsolatedRedis() throws Exception {
        try (IsolatedRedisServer redis = new IsolatedRedisServer()) {
            Path file = Files.createTempFile("saci-loader", ".properties");
            Properties p = new Properties();
            p.setProperty("redis.host", "127.0.0.1");
            p.setProperty("redis.port", Integer.toString(redis.port()));
            p.setProperty("redis.username", "");
            p.setProperty("redis.password", "");
            p.setProperty("redis.database", "15");
            p.setProperty("redis.ssl", "false");
            p.setProperty("redis.connect-timeout-ms", "2000");
            p.setProperty("redis.socket-timeout-ms", "2000");
            p.setProperty("redis.game-id", "61");
            p.setProperty("generation.loss-count", "3");
            p.setProperty("generation.win-count", "2");
            p.setProperty("generation.special-count", "1");
            p.setProperty("generation.batch-size", "10");
            p.setProperty("generation.max-members-per-multiplier", "300");
            p.setProperty("generation.max-consecutive-wins", "40");
            p.setProperty("generation.normal-max-win-multiplier", "5000");
            p.setProperty("generation.special-max-win-multiplier", "20000");
            try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) { p.store(writer, null); }
            RedisLoader.LoadSummary summary = new RedisLoader().load(GeneratorConfig.load(file));
            assertEquals(3, summary.lossMembers());
            assertEquals(2, summary.winMembers());
            assertEquals(1, summary.specialMembers());
            assertTrue(redis.lists.values().stream().flatMap(java.util.Collection::stream)
                    .allMatch(v -> v.startsWith("SACIA1;")));
        }
    }
}
