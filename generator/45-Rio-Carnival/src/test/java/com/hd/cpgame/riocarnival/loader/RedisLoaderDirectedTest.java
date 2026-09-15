package com.hd.cpgame.riocarnival.loader;

import com.hd.cpgame.riocarnival.core.GameRules;
import com.hd.cpgame.riocarnival.core.GeneratedRound;
import com.hd.cpgame.riocarnival.core.RandomSource;
import com.hd.cpgame.riocarnival.core.RoundFactsCodec;
import com.hd.cpgame.riocarnival.core.RoundResult;
import com.hd.cpgame.riocarnival.core.RoundVerifier;
import com.hd.cpgame.riocarnival.core.RandomBoardCandidateGenerator;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class RedisLoaderDirectedTest {
    @Test void empiricalDealingModelProducesEverySymbolAndRejectsPerCellOverrides() {
        for (String symbol : GameRules.SYMBOLS) {
            assertTrue(GameRules.DEFAULT_NORMAL_WEIGHTS.get(symbol) > 0, symbol);
            assertTrue(GameRules.DEFAULT_FREE_WEIGHTS.get(symbol) > 0, symbol);
        }
        RandomBoardCandidateGenerator generator = new RandomBoardCandidateGenerator(new DeterministicRandom(450045L));
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (String symbol : GameRules.SYMBOLS) counts.put(symbol, 0);
        for (int i = 0; i < 10_000; i++) {
            for (String symbol : generator.nextBoard(false, false)) counts.put(symbol, counts.get(symbol) + 1);
        }
        for (String symbol : GameRules.SYMBOLS) assertTrue(counts.get(symbol) > 0, symbol);
        assertNotEquals(counts.get("Scat"), counts.get("H1"));
        Map<String,Integer> changed=new LinkedHashMap<String,Integer>(GameRules.DEFAULT_NORMAL_WEIGHTS);
        changed.put("9",changed.get("9")+1);
        assertThrows(IllegalArgumentException.class,()->new RandomBoardCandidateGenerator(new DeterministicRandom(1),changed,GameRules.DEFAULT_FREE_WEIGHTS));
    }

    @Test void configurationRejectsOldJsonlSwitchesSeedZeroWeightsAndOversizedTargets() throws Exception {
        Properties base = productionProperties();
        assertConfigRejected(base, "output.file", "rounds.jsonl");
        assertConfigRejected(base, "seed", "45");
        assertConfigRejected(base, "generation.symbol.Wild.normal-weight", "0");
        assertConfigRejected(base, "generation.normal-count", "2147483648");
    }

    @Test void isolatedRedisReceivesVerifiedCompleteRoundsWithAtomicTrimmedBuckets() throws Exception {
        try (IsolatedRedisServer redis = new IsolatedRedisServer()) {
            Properties p = productionProperties();
            p.setProperty("redis.host", "127.0.0.1");
            p.setProperty("redis.port", Integer.toString(redis.port()));
            p.setProperty("redis.database", "0");
            p.setProperty("generation.normal-count", "8");
            p.setProperty("generation.special-count", "2");
            p.setProperty("generation.loss-count", "8");
            p.setProperty("generation.batch-size", "3");
            p.setProperty("generation.max-members-per-multiplier", "2");
            Path config = write(p);
            RedisLoader.LoadSummary summary = new RedisLoader().load(GeneratorConfig.loadForTest(config));

            assertEquals(Integer.parseInt(p.getProperty("generation.loss-count")), summary.lossMembers);
            assertEquals(8, summary.normalMembers);
            assertEquals(2, summary.specialMembers);
            assertTrue(summary.batches >= 4);
            assertTrue(redis.zsets.containsKey("Rio45:v3:normal:ratios"));
            assertTrue(redis.zsets.containsKey("Rio45:v3:special:ratios"));
            assertEquals(summary.batches, redis.transactions.size());
            for (List<List<String>> transaction : redis.transactions) {
                assertEquals(0, transaction.size() % 3);
                for (int i = 0; i < transaction.size(); i += 3) {
                    assertEquals("ZADD", transaction.get(i).get(0));
                    assertEquals("RPUSH", transaction.get(i + 1).get(0));
                    assertEquals("LTRIM", transaction.get(i + 2).get(0));
                    assertEquals(transaction.get(i + 1).get(1), transaction.get(i + 2).get(1));
                }
            }

            RoundFactsCodec codec = new RoundFactsCodec();
            int normalMembers = 0;
            int specialMembers = 0;
            for (Map.Entry<String, List<String>> entry : redis.lists.entrySet()) {
                assertTrue(entry.getValue().size() <= 2, entry.getKey());
                boolean special = entry.getKey().startsWith("Rio45:v3:special:");
                for (String payload : entry.getValue()) {
                    GeneratedRound round = codec.decode(payload);
                    RoundResult result = RoundVerifier.verify(round);
                    assertTrue(round.terminal());
                    assertEquals(special, "FREE_SPINS".equals(result.mode));
                    assertTrue(result.redisRatio(round) >= 0);
                    assertTrue(entry.getKey().endsWith(":"+result.redisRatio(round)));
                    if (special) { assertTrue(round.steps.size() > 1); specialMembers++; }
                    else { assertEquals(1, round.steps.size()); normalMembers++; }
                }
            }
            assertTrue(normalMembers > 0);
            assertTrue(specialMembers > 0);
            redis.assertHealthy();
        }
    }

    private static void assertConfigRejected(Properties source, String key, String value) throws Exception {
        Properties copy = new Properties();
        copy.putAll(source);
        copy.setProperty(key, value);
        Path file = write(copy);
        assertThrows(IllegalArgumentException.class, () -> GeneratorConfig.load(file), key);
    }

    private static Properties productionProperties() throws Exception {
        Properties p = new Properties();
        Path source = Paths.get("src", "dist", "generator.properties");
        try (java.io.Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) { p.load(reader); }
        return p;
    }

    private static Path write(Properties p) throws Exception {
        Path file = Files.createTempFile("rio-carnival-generator-", ".properties");
        try (java.io.Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) { p.store(writer, "test"); }
        file.toFile().deleteOnExit();
        return file;
    }

    private static final class DeterministicRandom implements RandomSource {
        private final java.util.Random random;
        DeterministicRandom(long seed) { random = new java.util.Random(seed); }
        @Override public int nextInt(int bound) { return random.nextInt(bound); }
    }
}
