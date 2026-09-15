package com.cpgame.luckycatii;

import com.cpgame.luckycatii.model.RoundResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class RedisLoaderTest {
    @Test void configurationRejectsSeedJsonlAndUnknownKeys() throws Exception {
        Properties base = productionProperties();
        assertRejected(base, "seed", "50");
        assertRejected(base, "output.file", "rounds.jsonl");
        assertRejected(base, "generation.loss-count", "0");
        assertRejected(base, "generation.special-count", "2147483648");
        assertRejected(base, "redis.game-id", "41");
    }

    @Test void isolatedRedisReceivesVerifiedAsciiMembersIncludingLosses() throws Exception {
        try (IsolatedRedisServer redis = new IsolatedRedisServer()) {
            Properties p = productionProperties();
            p.setProperty("redis.host", "127.0.0.1");
            p.setProperty("redis.port", Integer.toString(redis.port()));
            p.setProperty("redis.database", "0");
            p.setProperty("generation.loss-count", "6");
            p.setProperty("generation.win-count", "6");
            p.setProperty("generation.special-count", "4");
            p.setProperty("generation.batch-size", "4");
            p.setProperty("generation.max-members-per-multiplier", "2");
            RedisLoader.LoadSummary summary = new RedisLoader().load(GeneratorConfig.load(write(p)));
            assertEquals(6, summary.lossMembers());
            assertEquals(6, summary.winMembers());
            assertEquals(4, summary.specialMembers());
            assertTrue(summary.luckyMembers() + summary.wheelMembers() >= 4);
            assertTrue(redis.zsets.containsKey("PerKeyList_000000050"));
            assertTrue(redis.zsets.containsKey("MaryKeyList_000000050"));
            assertTrue(redis.zsets.get("PerKeyList_000000050").contains("0"));
            MinimalRoundFactCodec codec = new MinimalRoundFactCodec(new RoundFactory(), new RoundVerifier());
            int retained = 0;
            for (Map.Entry<String, List<String>> entry : redis.lists.entrySet()) {
                assertTrue(entry.getValue().size() <= 2);
                for (String member : entry.getValue()) {
                    assertFalse(member.startsWith("{"));
                    RoundResult round = codec.decodeRedisMember(member);
                    new RoundVerifier().verify(round);
                    retained++;
                }
            }
            assertTrue(retained >= 6 && retained <= 16, "LTRIM 后仍应保留完整局: " + retained);
            for (List<List<String>> transaction : redis.transactions) {
                assertEquals(0, transaction.size() % 3);
            }
            redis.assertHealthy();
        }
    }

    private static Properties productionProperties() throws Exception {
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("dist", "generator.properties"), StandardCharsets.UTF_8)) {
            p.load(reader);
        }
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
        Path file = Files.createTempFile("lucky-cat-ii-gen-", ".properties");
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) { p.store(writer, "test"); }
        file.toFile().deleteOnExit();
        return file;
    }
}
