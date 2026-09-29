package com.cpgame.crazybirds.generator;

import com.cpgame.crazybirds.generator.model.RoundResult;
import com.cpgame.crazybirds.generator.model.RoundMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuleBasedGenerationTest {
    @Test
    void redisKeysKeepExistingPerAndMaryFamilies() {
        long gameId = 8_000_060L;

        assertEquals("008000060", RedisLoader.poolPrefix(gameId, GameRules.NORMAL_POOL_TYPE));
        assertEquals("PerKeyList_008000060", RedisLoader.normalIndex(gameId, GameRules.NORMAL_POOL_TYPE));
        assertEquals("BetLog:008000060:000025",
                RedisLoader.normalList(gameId, GameRules.NORMAL_POOL_TYPE, 25));
        assertEquals("MaryKeyList_008000060",
                RedisLoader.maryIndex(gameId, GameRules.FREE_SPINS_MARY_POOL_TYPE));
        assertEquals("MaryLog:008000060:000500",
                RedisLoader.maryList(gameId, GameRules.FREE_SPINS_MARY_POOL_TYPE, 500));
    }

    @Test
    void batchPhasesAreNeutralThenOneSymbolBoostedThenReset() throws Exception {
        GeneratorConfig config = config();
        GenerationWeights.BatchWeights neutral = config.weights.forBatch(0);
        assertEquals(17, config.weights.phaseCount());
        assertEquals(null, neutral.boostedSymbol());

        for (int phase = 1; phase <= GameRules.ALL_SYMBOLS.size(); phase++) {
            GenerationWeights.BatchWeights boosted = config.weights.forBatch(phase);
            assertEquals(GameRules.ALL_SYMBOLS.get(phase - 1), boosted.boostedSymbol());
            for (int i = 0; i < GameRules.ALL_SYMBOLS.size(); i++) {
                long multiplier = i == phase - 1 ? 3L : 1L;
                assertEquals(neutral.paid()[i] * multiplier, boosted.paid()[i]);
                assertEquals(neutral.free()[i] * multiplier, boosted.free()[i]);
            }
        }

        GenerationWeights.BatchWeights reset = config.weights.forBatch(config.weights.phaseCount());
        assertEquals(null, reset.boostedSymbol());
        assertArrayEquals(neutral.paid(), reset.paid());
        assertArrayEquals(neutral.free(), reset.free());
    }

    @Test
    void multiplierRangeUsesConfiguredCacheIntegerBoundaries() throws Exception {
        GeneratorConfig config = config();

        assertTrue(config.acceptsCacheMultiplier(RoundMode.ORDINARY_LOSS, 0));
        assertTrue(config.acceptsCacheMultiplier(RoundMode.ORDINARY_WIN,
                config.normalMinCacheMultiplier));
        assertTrue(config.acceptsCacheMultiplier(RoundMode.ORDINARY_WIN,
                config.normalMaxCacheMultiplier));
        if (config.normalMinCacheMultiplier > 0) {
            assertFalse(config.acceptsCacheMultiplier(RoundMode.ORDINARY_WIN,
                    config.normalMinCacheMultiplier - 1));
        }
        assertFalse(config.acceptsCacheMultiplier(RoundMode.ORDINARY_WIN,
                config.normalMaxCacheMultiplier + 1));
        assertTrue(config.acceptsCacheMultiplier(RoundMode.FREE_SPINS,
                config.freeMinCacheMultiplier));
        assertTrue(config.acceptsCacheMultiplier(RoundMode.FREE_SPINS,
                config.freeMaxCacheMultiplier));
        if (config.freeMinCacheMultiplier > 0) {
            assertFalse(config.acceptsCacheMultiplier(RoundMode.FREE_SPINS, 0));
        }
        if (config.freeMinCacheMultiplier > 0) {
            assertFalse(config.acceptsCacheMultiplier(RoundMode.FREE_SPINS,
                    config.freeMinCacheMultiplier - 1));
        }
        assertFalse(config.acceptsCacheMultiplier(RoundMode.FREE_SPINS,
                config.freeMaxCacheMultiplier + 1));
    }

    @Test
    void decimalMultiplierConfigurationIsRejected() throws Exception {
        Path source = Path.of("dist", "generator.properties");
        Path temporary = Files.createTempFile("crazy-birds-decimal-range-", ".properties");
        try {
            String invalid = Files.readString(source, StandardCharsets.UTF_8)
                    .replaceFirst("generation\\.normal-min-win-multiplier=\\d+",
                            "generation.normal-min-win-multiplier=0.25");
            Files.writeString(temporary, invalid, StandardCharsets.UTF_8);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> GeneratorConfig.load(temporary));
            assertTrue(error.getMessage().contains("必须为整数"));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Test
    void auditUsesRealGeneratorAndReportsDiverseFactsModesAndPhases() throws Exception {
        GeneratorConfig config = config();
        long attempts = (long) config.batchSize * config.weights.phaseCount();
        RedisLoader.LoadSummary summary = new RedisLoader().audit(config, attempts, new Random(60060L));

        assertEquals(attempts, summary.attempts());
        assertEquals(config.weights.phaseCount(), summary.batches());
        assertEquals(attempts, summary.accepted() + summary.rejected());
        assertEquals(summary.accepted(), summary.uniqueFacts());
        assertTrue(summary.modes().getOrDefault("ORDINARY_LOSS", 0L) > 0);
        assertTrue(summary.modes().getOrDefault("ORDINARY_WIN", 0L) > 0);
        assertTrue(summary.modes().getOrDefault("FREE_SPINS", 0L) > 0);
        assertTrue(summary.maxDeliveries() >= GameRules.BASE_FREE_SPINS + 1L);
        assertTrue(summary.normalDistribution().size() > 10);
        assertTrue(summary.freeDistribution().size() > 10);
        assertEquals(config.weights.phaseCount(), summary.phaseAttempts().size());
        assertEquals((long) config.batchSize, summary.phaseAttempts().get("NEUTRAL"));
        for (String symbol : GameRules.ALL_SYMBOLS) {
            assertEquals((long) config.batchSize, summary.phaseAttempts().get("BOOST_" + symbol));
        }
    }

    @Test
    void compactCodecHasNoRepeatedHeaderAndRejectsOldPrefixedMember() throws Exception {
        GeneratorConfig config = config();
        RuleBasedRoundGenerator generator = new RuleBasedRoundGenerator();
        RoundResult round = generator.generate(1, BigDecimal.ONE, new BigDecimal("10000"),
                config.weights.forBatch(0), new Random(60L));
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec(new RoundFactory(), new RoundVerifier());

        String payload = codec.encodeRedisMemberString(round);
        RoundResult rebuilt = codec.decodeRedisMember(payload, round.bs(), round.bl(), round.startingBalance());
        new RoundVerifier().verifyRecovery(round, rebuilt);
        assertFalse(payload.contains(","));
        assertFalse(payload.contains(";"));
        assertFalse(payload.startsWith("CB60"));
        assertEquals(round.boards().size() * GameRules.BOARD_SIZE + round.boards().size() - 1,
                payload.length());
        long separators = payload.chars().filter(c -> c == '|').count();
        assertTrue(separators * 5 <= payload.length(), "分隔符占比不得超过20%");
        assertThrows(IllegalArgumentException.class,
                () -> codec.decodeBoards("CB60B1;sha256:old;" + payload));
    }

    @Test
    void reportedSingleStepExampleIsStoredAsExactlyIts24FactCharacters() {
        String member = "47657F105465785215146159";
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec(new RoundFactory(), new RoundVerifier());

        RoundResult round = codec.decodeRedisMember(member, BigDecimal.ONE, 1, new BigDecimal("10000"));

        assertEquals(24, member.length());
        assertEquals(member, codec.encodeRedisMemberString(round));
    }

    private static GeneratorConfig config() throws Exception {
        return GeneratorConfig.load(Path.of("dist", "generator.properties"));
    }
}
