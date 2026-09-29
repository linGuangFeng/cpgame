package com.cpgame.crazy777.generator;

import com.cpgame.crazy777.generator.model.ResultAnalysis;
import com.cpgame.crazy777.generator.model.RoundMode;
import com.cpgame.crazy777.generator.model.RoundResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class RedisLoaderTest {
    @Test void configurationRejectsSeedAndInvalidCounts() throws Exception {
        Properties base = productionProperties();
        assertRejected(base, "seed", "57");
        assertRejected(base, "generation.loss-count", "2147483648");
        assertRejected(base, "generation.special-count", "0");
    }

    @Test void isolatedRedisOrdinaryPathWritesOnlyPerKeyList() throws Exception {
        try (IsolatedRedisServer redis = new IsolatedRedisServer()) {
            GeneratorConfig config = loadIsolated(redis, "20", "2", "3");
            RedisLoader.LoadSummary summary = new RedisLoader().loadOrdinary(config);

            assertEquals(0, summary.lossMembers());
            assertEquals(0, summary.specialMembers());
            assertTrue(summary.winMembers() > 6, "ordinary enumeration ignores win-count, got " + summary.winMembers());
            assertTrue(summary.normalDistribution().values().stream().allMatch(n -> n <= 2));
            assertTrue(summary.normalDistribution().keySet().stream().noneMatch(ratio -> ratio < 1 || ratio > 2500));
            assertTrue(redis.zsets.containsKey(RedisLoader.normalIndex(config.redisGameId)));
            assertFalse(redis.zsets.containsKey(RedisLoader.specialIndex(config.redisGameId)));
            assertCommands(redis);
            int ordinary = decodePool(redis, false, 2);
            assertEquals(summary.winMembers() + summary.lossMembers(), ordinary);
        }
    }

    @Test void isolatedRedisMaryPathWritesOnlyMaryKeyList() throws Exception {
        try (IsolatedRedisServer redis = new IsolatedRedisServer()) {
            GeneratorConfig config = loadIsolated(redis, "20", "2", "3");
            RedisLoader.LoadSummary summary = new RedisLoader().loadMary(config);

            assertEquals(0, summary.lossMembers());
            assertEquals(0, summary.winMembers());
            assertEquals(3, summary.specialMembers());
            assertTrue(summary.specialDistribution().keySet().stream().noneMatch(ratio -> ratio < 25 || ratio > 2500));
            assertTrue(redis.zsets.containsKey(RedisLoader.specialIndex(config.redisGameId)));
            assertFalse(redis.zsets.containsKey(RedisLoader.normalIndex(config.redisGameId)));
            assertCommands(redis);
            assertEquals(3, decodePool(redis, true, 100));
        }
    }

    @Test void loaderMainAcceptsPropertiesOnlyAsBothPools() {
        LoaderMain.Parsed ordinary = LoaderMain.parse(new String[]{"ordinary"});
        assertEquals(RedisLoader.Pool.ORDINARY, ordinary.pool());
        assertFalse(ordinary.both());
        assertEquals(Path.of("generator.properties"), ordinary.config());

        LoaderMain.Parsed mary = LoaderMain.parse(new String[]{"mary", "generator.properties"});
        assertEquals(RedisLoader.Pool.MARY, mary.pool());
        assertFalse(mary.both());
        assertEquals(Path.of("generator.properties"), mary.config());

        LoaderMain.Parsed propertiesOnly = LoaderMain.parse(new String[]{"generator.properties"});
        assertTrue(propertiesOnly.both());
        assertNull(propertiesOnly.pool());
        assertEquals(Path.of("generator.properties"), propertiesOnly.config());

        LoaderMain.Parsed absolute = LoaderMain.parse(new String[]{"D:\\work\\hd\\cpgame\\generator\\57-Crazy-777\\dist\\generator.properties"});
        assertTrue(absolute.both());
        assertEquals(Path.of("D:\\work\\hd\\cpgame\\generator\\57-Crazy-777\\dist\\generator.properties"), absolute.config());

        LoaderMain.Parsed both = LoaderMain.parse(new String[]{"both", "custom.properties"});
        assertTrue(both.both());
        assertNull(both.pool());
        assertEquals(Path.of("custom.properties"), both.config());

        assertThrows(IllegalArgumentException.class, () -> LoaderMain.parse(new String[]{}));
        assertThrows(IllegalArgumentException.class, () -> LoaderMain.parse(new String[]{"ordinary", "a", "b"}));
        assertThrows(IllegalArgumentException.class, () -> LoaderMain.parse(new String[]{"--config", "generator.properties"}));
    }

    @Test void trainingKernelsAreCompleteJointStates() {
        assertTrue(new RandomCandidateGenerator().trainingKernelCount() >= 1200);
        assertTrue(new RandomCandidateGenerator().lossKernelCount() >= 1000);
        assertTrue(new RandomCandidateGenerator().winKernelCount() >= 100);
        assertTrue(new RandomCandidateGenerator().freeKernelCount() >= 30);
    }

    @Test void configuredWeightsChangeCompleteKernelSelection() {
        SymbolWeights base = SymbolWeights.empiricalDefaults();
        Map<String, Integer> boostedNormal = new LinkedHashMap<>(base.normal());
        boostedNormal.compute("H1", (key, value) -> value * 20);
        RandomCandidateGenerator normal = new RandomCandidateGenerator(base);
        RandomCandidateGenerator boosted = new RandomCandidateGenerator(
                new SymbolWeights(boostedNormal, base.entry(), base.free()));
        int normalH1 = 0, boostedH1 = 0;
        var normalRandom = new java.util.SplittableRandom(570099L);
        var boostedRandom = new java.util.SplittableRandom(570099L);
        for (int i = 0; i < 1000; i++) {
            normalH1 += java.util.Collections.frequency(normal.ordinaryWin(normalRandom).boards().get(0), "H1");
            boostedH1 += java.util.Collections.frequency(boosted.ordinaryWin(boostedRandom).boards().get(0), "H1");
        }
        assertTrue(boostedH1 > normalH1 * 3 / 2, normalH1 + " -> " + boostedH1);
    }

    private static GeneratorConfig loadIsolated(IsolatedRedisServer redis, String batch, String cap, String special)
            throws Exception {
        Properties p = productionProperties();
        p.setProperty("redis.host", "127.0.0.1");
        p.setProperty("redis.port", Integer.toString(redis.port()));
        p.setProperty("redis.database", "0");
        p.setProperty("generation.loss-count", "6");
        p.setProperty("generation.win-count", "6");
        p.setProperty("generation.special-count", special);
        p.setProperty("generation.batch-size", batch);
        p.setProperty("generation.max-members-per-multiplier", cap);
        return GeneratorConfig.load(write(p));
    }

    private static void assertCommands(IsolatedRedisServer redis) {
        assertFalse(redis.transactions.isEmpty());
        for (List<List<String>> transaction : redis.transactions) {
            assertEquals(0, transaction.size() % 3);
            for (int i = 0; i < transaction.size(); i += 3) {
                assertEquals("ZADD", transaction.get(i).get(0));
                assertEquals("RPUSH", transaction.get(i + 1).get(0));
                assertEquals("LTRIM", transaction.get(i + 2).get(0));
            }
        }
    }

    private static int decodePool(IsolatedRedisServer redis, boolean mary, int cap) {
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec(new RoundFactory(), new RoundVerifier());
        int members = 0;
        for (Map.Entry<String, List<String>> entry : redis.lists.entrySet()) {
            assertEquals(mary, entry.getKey().startsWith("MaryLog"));
            assertTrue(entry.getValue().size() <= cap, entry.getKey() + " size=" + entry.getValue().size());
            for (String payload : entry.getValue()) {
                assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(payload));
                assertFalse(payload.startsWith("{"));
                assertFalse(payload.contains(","));
                RoundResult round = codec.decodeRedisMember(payload);
                ResultAnalysis analysis = new RoundVerifier().verify(round);
                if (mary) {
                    assertEquals(RoundMode.FREE_SPINS, analysis.mode());
                    assertTrue(ResultUtil.isScatterTrigger(round.boards().get(0)));
                } else {
                    assertNotEquals(RoundMode.FREE_SPINS, analysis.mode());
                    assertFalse(ResultUtil.isScatterTrigger(round.boards().get(0)));
                }
                members++;
            }
        }
        return members;
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
