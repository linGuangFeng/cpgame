package com.cpgame.crazy777.generator;

import com.cpgame.crazy777.generator.model.RoundMode;
import com.cpgame.crazy777.generator.model.RoundResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class RedisLoaderTest {
    @Test void configurationRejectsJsonlSeedSwitchesAndOversizedTargets() throws Exception {
        Properties base = productionProperties();
        assertRejected(base, "output.file", "rounds.jsonl");
        assertRejected(base, "seed", "57");
        assertRejected(base, "redis.enabled", "false");
        assertRejected(base, "generation.loss-count", "2147483648");
        assertRejected(base, "generation.special-count", "0");
    }

    @Test void isolatedRedisReceivesVerifiedCompleteRoundsWithAtomicTrimmedBuckets() throws Exception {
        try (IsolatedRedisServer redis = new IsolatedRedisServer()) {
            Properties p = productionProperties();
            p.setProperty("redis.host", "127.0.0.1");
            p.setProperty("redis.port", Integer.toString(redis.port()));
            p.setProperty("redis.database", "0");
            p.setProperty("generation.loss-count", "6");
            p.setProperty("generation.win-count", "6");
            p.setProperty("generation.special-count", "3");
            p.setProperty("generation.batch-size", "4");
            p.setProperty("generation.max-members-per-multiplier", "2");
            RedisLoader.LoadSummary summary = new RedisLoader().load(GeneratorConfig.load(write(p)));

            assertEquals(6, summary.lossMembers());
            assertEquals(6, summary.winMembers());
            assertEquals(3, summary.specialMembers());
            assertEquals(4, summary.batches());
            assertTrue(redis.zsets.containsKey("PerKeyList_000000057"));
            assertTrue(redis.zsets.containsKey("MaryKeyList_000000057"));
            assertEquals(4, redis.transactions.size());
            for (List<List<String>> transaction : redis.transactions) {
                assertEquals(0, transaction.size() % 3);
                for (int i = 0; i < transaction.size(); i += 3) {
                    assertEquals("ZADD", transaction.get(i).get(0));
                    assertEquals("RPUSH", transaction.get(i + 1).get(0));
                    assertEquals("LTRIM", transaction.get(i + 2).get(0));
                }
            }
            MinimalRoundFactCodec codec = new MinimalRoundFactCodec(new RoundFactory(), new RoundVerifier());
            int members = 0;
            for (Map.Entry<String, List<String>> entry : redis.lists.entrySet()) {
                assertTrue(entry.getValue().size() <= 2);
                for (String payload : entry.getValue()) {
                    assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(payload));
                    assertFalse(payload.startsWith("{"));
                    RoundResult round = codec.decodeRedisMember(payload);
                    new RoundVerifier().verify(round);
                    members++;
                }
            }
            assertTrue(members >= 7 && members <= 15, "trimmed members=" + members);
        }
    }

    @Test void trainingKernelsAreCompleteJointStates() {
        assertTrue(new RandomCandidateGenerator().trainingKernelCount() >= 1200);
        assertTrue(new RandomCandidateGenerator().lossKernelCount() >= 1000);
        assertTrue(new RandomCandidateGenerator().winKernelCount() >= 100);
        assertTrue(new RandomCandidateGenerator().freeKernelCount() >= 30);
    }

    private static Properties productionProperties() throws Exception {
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("dist", "generator.properties"))) { p.load(reader); }
        return p;
    }

    private static void assertRejected(Properties base, String key, String value) throws Exception {
        Properties p = new Properties();
        p.putAll(base);
        p.setProperty(key, value);
        Path file = write(p);
        assertThrows(IllegalArgumentException.class, () -> GeneratorConfig.load(file));
    }

    private static Path write(Properties p) throws Exception {
        Path file = Files.createTempFile("crazy777-gen", ".properties");
        try (var writer = Files.newBufferedWriter(file)) { p.store(writer, null); }
        return file;
    }
}
