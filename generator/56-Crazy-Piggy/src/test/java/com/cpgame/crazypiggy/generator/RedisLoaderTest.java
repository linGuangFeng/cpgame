package com.cpgame.crazypiggy.generator;

import com.cpgame.crazypiggy.generator.model.RoundMode;
import com.cpgame.crazypiggy.generator.model.RoundResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class RedisLoaderTest {
    @Test void productionWeightsArePositiveNonUniformActuallyUsedAndDistributed() throws Exception {
        GeneratorConfig config = GeneratorConfig.load(Path.of("dist", "generator.properties"));
        for (int weight : config.weights.modeWeights().values()) assertTrue(weight > 0);
        assertTrue(new RandomCandidateGenerator(config.weights).trainingKernelCount() >= 1292);

        GameRuleCore core = GameRuleCore.forTesting(560050L, config.weights);
        RoundVerifier verifier = new RoundVerifier();
        Map<String, Integer> observed = new LinkedHashMap<>();
        GameRules.SYMBOLS.forEach(symbol -> observed.put(symbol, 0));
        int special = 0;
        for (int i = 0; i < 20_000; i++) {
            RoundResult round = core.generatePaidRound(new BigDecimal("0.5"), 1);
            if (verifier.verify(round).mode() == RoundMode.BOOSTER_WHEEL) special++;
            round.symbols().forEach(symbol -> observed.merge(symbol, 1, Integer::sum));
        }
        GameRules.SYMBOLS.forEach(symbol -> assertTrue(observed.get(symbol) > 0, observed.toString()));
        assertTrue(special > 200 && special < 700, "特殊模式分布异常: " + special);
    }

    @Test void configurationRejectsJsonlSeedSwitchesZeroWeightsAndOversizedTargets() throws Exception {
        Properties base = productionProperties();
        assertRejected(base, "output.file", "rounds.jsonl");
        assertRejected(base, "seed", "56");
        assertRejected(base, "redis.enabled", "false");
        assertRejected(base, "write-enabled", "false");
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
            assertTrue(redis.zsets.containsKey("PerKeyList_000000056"));
            assertTrue(redis.zsets.containsKey("MaryKeyList_000000056"));
            assertEquals(4, redis.transactions.size());
            for (List<List<String>> transaction : redis.transactions) {
                assertEquals(0, transaction.size() % 3);
                for (int i = 0; i < transaction.size(); i += 3) {
                    assertEquals("ZADD", transaction.get(i).get(0));
                    assertEquals("RPUSH", transaction.get(i + 1).get(0));
                    assertEquals("LTRIM", transaction.get(i + 2).get(0));
                    assertEquals(transaction.get(i + 1).get(1), transaction.get(i + 2).get(1));
                }
            }

            MinimalRoundFactCodec codec = new MinimalRoundFactCodec(new RoundFactory(), new RoundVerifier());
            int retainedNormal = 0, retainedSpecial = 0;
            for (Map.Entry<String, List<String>> entry : redis.lists.entrySet()) {
                assertTrue(entry.getValue().size() <= 2, entry.getKey());
                boolean special = entry.getKey().startsWith("MaryLog:");
                for (String payload : entry.getValue()) {
                    RoundResult round = codec.decodeRedisMember(payload);
                    assertEquals(special, round.boosterWheel());
                    assertTrue(payload.startsWith(MinimalRoundFactCodec.PREFIX + ";"));
                    if (special) { assertFalse(round.deliveries().isEmpty()); retainedSpecial++; }
                    else { assertTrue(round.deliveries().isEmpty()); retainedNormal++; }
                }
            }
            assertTrue(retainedNormal > 0 && retainedSpecial > 0);
            redis.assertHealthy();
        }
    }

    private static void assertRejected(Properties source, String key, String value) throws Exception {
        Properties copy = new Properties();
        copy.putAll(source);
        copy.setProperty(key, value);
        assertThrows(IllegalArgumentException.class, () -> GeneratorConfig.load(write(copy)), key);
    }

    private static Properties productionProperties() throws Exception {
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("dist", "generator.properties"), StandardCharsets.UTF_8)) {
            p.load(reader);
        }
        return p;
    }

    private static Path write(Properties p) throws Exception {
        Path file = Files.createTempFile("crazy-piggy-generator-", ".properties");
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) { p.store(writer, "test"); }
        file.toFile().deleteOnExit();
        return file;
    }
}
