package com.cpgame.crazypiggy.generator;

import com.cpgame.crazypiggy.generator.model.RoundMode;
import com.cpgame.crazypiggy.generator.model.RoundResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
    @Test void productionWeightsRemainAvailableForKernelEntry() throws Exception {
        GeneratorConfig config = GeneratorConfig.load(Path.of("dist", "generator.properties"));
        for (int weight : config.weights.modeWeights().values()) assertTrue(weight > 0);
        assertTrue(new RandomCandidateGenerator(config.weights).trainingKernelCount() >= 1292);
        assertEquals(0, config.outputLimits.accepts(false, 0) ? 0 : 1);
        assertTrue(config.outputLimits.accepts(false, 1));
        assertTrue(config.outputLimits.accepts(false, 2500));
        assertFalse(config.outputLimits.accepts(false, 2501));
        assertEquals(300, config.maxMembersPerMultiplier);

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

    @Test void symbolWeightsChangeCompleteKernelSelection() {
        GenerationWeights base = GenerationWeights.defaults();
        Map<String, Integer> boostedNormal = new LinkedHashMap<>(base.symbolWeights());
        boostedNormal.compute("HOT", (key, value) -> value * 20);
        GenerationWeights boostedWeights = new GenerationWeights(base.modeWeights(), boostedNormal,
                base.boosterSymbolWeights());
        RandomCandidateGenerator normal = new RandomCandidateGenerator(base);
        RandomCandidateGenerator boosted = new RandomCandidateGenerator(boostedWeights);
        int normalHot = 0, boostedHot = 0;
        var normalRandom = new java.util.SplittableRandom(560099L);
        var boostedRandom = new java.util.SplittableRandom(560099L);
        for (int i = 0; i < 1000; i++) {
            normalHot += java.util.Collections.frequency(normal.ordinaryWin(normalRandom).symbols(), "HOT");
            boostedHot += java.util.Collections.frequency(boosted.ordinaryWin(boostedRandom).symbols(), "HOT");
        }
        assertTrue(boostedHot > normalHot * 3 / 2, normalHot + " -> " + boostedHot);
    }

    @Test void configurationRejectsSeedAndIgnoresGenerationCounts() throws Exception {
        Properties base = productionProperties();
        assertThrows(IllegalArgumentException.class, () -> GeneratorConfig.load(write(with(base, "seed", "56"))));
        assertDoesNotThrow(() -> GeneratorConfig.load(write(with(base, "generation.loss-count", "1"))));
        assertDoesNotThrow(() -> GeneratorConfig.load(write(with(base, "generation.win-count", "1"))));
        assertDoesNotThrow(() -> GeneratorConfig.load(write(with(base, "generation.special-count", "0"))));
        assertDoesNotThrow(() -> GeneratorConfig.load(write(with(base, "generation.loss-count", "2147483648"))));
    }

    @Test
    @Timeout(180)
    void isolatedRedisReceivesVerifiedCompleteRoundsInOrdinaryPool() throws Exception {
        try (IsolatedRedisServer redis = new IsolatedRedisServer()) {
            Properties p = productionProperties();
            p.setProperty("redis.host", "127.0.0.1");
            p.setProperty("redis.port", Integer.toString(redis.port()));
            p.setProperty("redis.database", "0");
            p.setProperty("generation.loss-count", "1");
            p.setProperty("generation.win-count", "1");
            p.setProperty("generation.special-count", "1");
            p.setProperty("generation.batch-size", "4");
            p.setProperty("generation.max-members-per-multiplier", "2");
            p.setProperty("generation.normal-min-win-multiplier", "0");
            p.setProperty("generation.normal-max-win-multiplier", "25");
            GeneratorConfig config = GeneratorConfig.load(write(p));
            RedisLoader.LoadSummary summary = new RedisLoader().load(config);

            assertEquals(0, summary.specialMembers());
            assertTrue(summary.winMembers() > 1);
            assertTrue(summary.lossMembers() > 0);
            assertTrue(redis.zsets.containsKey(RedisLoader.normalIndex(config.redisGameId)));
            assertFalse(redis.zsets.containsKey(RedisLoader.specialIndex(config.redisGameId)));
            assertFalse(redis.lists.keySet().stream().anyMatch(k -> k.startsWith("MaryLog:")));
            assertTrue(summary.batches() > 0);
            for (List<List<String>> transaction : redis.transactions) {
                assertEquals(0, transaction.size() % 3);
                for (int i = 0; i < transaction.size(); i += 3) {
                    assertEquals("ZADD", transaction.get(i).get(0));
                    assertEquals("RPUSH", transaction.get(i + 1).get(0));
                    assertEquals("LTRIM", transaction.get(i + 2).get(0));
                    assertEquals(transaction.get(i + 1).get(1), transaction.get(i + 2).get(1));
                    assertTrue(transaction.get(i).get(1).startsWith("PerKeyList_"));
                    assertTrue(transaction.get(i + 1).get(1).startsWith("BetLog:"));
                }
            }

            MinimalRoundFactCodec codec = new MinimalRoundFactCodec(new RoundFactory(), new RoundVerifier());
            int retainedNormal = 0, retainedBooster = 0;
            for (Map.Entry<String, List<String>> entry : redis.lists.entrySet()) {
                assertTrue(entry.getValue().size() <= 2, entry.getKey());
                assertTrue(entry.getKey().startsWith("BetLog:"));
                for (String payload : entry.getValue()) {
                    RoundResult round = codec.decodeRedisMember(payload);
                    assertTrue(payload.equals("#") || payload.startsWith(MinimalRoundFactCodec.PREFIX + "|"));
                    assertFalse(payload.contains(","));
                    if (round.boosterWheel()) retainedBooster++;
                    else retainedNormal++;
                }
            }
            assertTrue(retainedNormal > 0 && retainedBooster > 0);
            redis.assertHealthy();
        }
    }

    private static Properties with(Properties source, String key, String value) {
        Properties copy = new Properties();
        copy.putAll(source);
        copy.setProperty(key, value);
        return copy;
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
