package com.cpgame.crazypiggy.generator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LoaderJarSmokeTest {
    @Test
    @Timeout(180)
    void deliveredFatJarRunsAgainstIsolatedRedis() throws Exception {
        Path jar = Path.of("dist", "crazy-piggy-loader.jar").toAbsolutePath();
        assertTrue(Files.isRegularFile(jar));
        try (IsolatedRedisServer redis = new IsolatedRedisServer()) {
            Properties p = new Properties();
            try (var reader = Files.newBufferedReader(Path.of("dist", "generator.properties"), StandardCharsets.UTF_8)) {
                p.load(reader);
            }
            p.setProperty("redis.host", "127.0.0.1");
            p.setProperty("redis.port", Integer.toString(redis.port()));
            p.setProperty("redis.database", "0");
            p.setProperty("generation.loss-count", "1");
            p.setProperty("generation.win-count", "1");
            p.setProperty("generation.special-count", "1");
            p.setProperty("generation.batch-size", "3");
            p.setProperty("generation.max-members-per-multiplier", "2");
            p.setProperty("generation.normal-min-win-multiplier", "0");
            p.setProperty("generation.normal-max-win-multiplier", "5");
            Path config = Files.createTempFile("crazy-piggy-jar-smoke-", ".properties");
            try (var writer = Files.newBufferedWriter(config, StandardCharsets.UTF_8)) { p.store(writer, "jar smoke"); }
            config.toFile().deleteOnExit();

            Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
            Process process = new ProcessBuilder(java.toString(), "-Dfile.encoding=UTF-8", "-jar",
                    jar.toString(), config.toString()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(process.waitFor(180, TimeUnit.SECONDS), "JAR Loader 超时");
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains(GameRules.RULES_HASH), output);
            assertTrue(output.contains("ENUMERATION_START"), output);
            long gameId = Long.parseLong(p.getProperty("redis.game-id"));
            assertTrue(redis.zsets.containsKey(RedisLoader.normalIndex(gameId)), output);
            assertFalse(redis.zsets.containsKey(RedisLoader.specialIndex(gameId)), output);
            assertFalse(redis.lists.keySet().stream().anyMatch(k -> k.startsWith("MaryLog:")));
            assertTrue(redis.transactions.size() > 0, output);
            redis.assertHealthy();
        }
    }
}
