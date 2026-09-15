package com.cpgame.beachfun.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class RedisLoaderTest {
    @Test void formalConfigurationMatches1809KeysAndRejectsSeedOrExcess() throws Exception {
        Path formal = Path.of("dist", "generator.properties").toAbsolutePath().normalize();
        RedisLoader.LoaderConfig config = RedisLoader.LoaderConfig.load(formal);
        assertEquals("192.168.10.3", config.host());
        assertEquals(6379, config.port());
        assertEquals(15, config.database());
        assertEquals(1940, config.redisGameId());
        assertEquals(100_000_000, config.normalCount());
        assertEquals(1_000_000, config.specialCount());
        assertEquals(100, config.batchSize());
        assertEquals(300, config.maxMembersPerMultiplier());
        assertEquals(14, config.maxConsecutiveWins());
        assertEquals(15, config.maxMarySpins());
        assertEquals(1000, config.entrySwitchEvery());
        assertEquals(1, config.normalMinWinMultiplier());
        assertEquals(20_000, config.normalMaxWinMultiplier());
        assertEquals(724, config.normalWeights()[8]);
        assertEquals(259, config.maryWeights()[8]);
        assertEquals(0, config.normalWeights()[9]);
        String text = Files.readString(formal, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        assertFalse(text.contains("seed=") || text.contains("redis.enabled") || text.contains("write-enabled"));
        assertTrue(text.contains("generation.normal-count"));
        assertTrue(text.contains("generation.special-count"));
        assertTrue(text.contains("generation.symbol.9.normal-weight"));

        Properties tooMany = sample(1);
        tooMany.setProperty("generation.normal-count", "2147483648");
        assertThrows(IllegalArgumentException.class, () -> RedisLoader.LoaderConfig.from(tooMany));
        Properties seed = sample(1);
        seed.setProperty("seed", "7");
        assertThrows(IllegalArgumentException.class, () -> RedisLoader.LoaderConfig.from(seed));
        Properties scatterZero = sample(1);
        scatterZero.setProperty("generation.symbol.9.normal-weight", "0");
        assertThrows(IllegalArgumentException.class, () -> RedisLoader.LoaderConfig.from(scatterZero));
        Properties special = sample(1);
        special.setProperty("generation.special-count", "2147483648");
        assertThrows(IllegalArgumentException.class, () -> RedisLoader.LoaderConfig.from(special));
    }

    @Test void multiplierCapReservesUntilFullThenRejects() {
        Map<Integer, Integer> counts = new HashMap<>();
        assertTrue(RedisLoader.tryReserveMultiplier(counts, 0, 2));
        assertTrue(RedisLoader.tryReserveMultiplier(counts, 0, 2));
        assertFalse(RedisLoader.tryReserveMultiplier(counts, 0, 2));
        assertEquals(2, counts.get(0));
        assertTrue(RedisLoader.tryReserveMultiplier(counts, 4, 2));
    }

    private static Properties sample(int normalCount) {
        Properties p = new Properties();
        p.setProperty("redis.host", "192.168.10.3");
        p.setProperty("redis.port", "6379");
        p.setProperty("redis.username", "");
        p.setProperty("redis.password", "");
        p.setProperty("redis.database", "15");
        p.setProperty("redis.ssl", "false");
        p.setProperty("redis.connect-timeout-ms", "5000");
        p.setProperty("redis.socket-timeout-ms", "30000");
        p.setProperty("redis.game-id", "1940");
        p.setProperty("generation.normal-count", Integer.toString(normalCount));
        p.setProperty("generation.special-count", "0");
        p.setProperty("generation.batch-size", "10");
        p.setProperty("generation.max-members-per-multiplier", "300");
        p.setProperty("generation.max-consecutive-wins", "14");
        p.setProperty("generation.max-mary-spins", "15");
        p.setProperty("generation.entry-switch-every", "1000");
        p.setProperty("generation.normal-min-win-multiplier", "1");
        p.setProperty("generation.normal-max-win-multiplier", "20000");
        p.setProperty("generation.mary-min-win-multiplier", "1");
        p.setProperty("generation.mary-max-win-multiplier", "20000");
        for (int s = 1; s <= 9; s++) {
            p.setProperty("generation.symbol." + s + ".normal-weight", "10");
            p.setProperty("generation.symbol." + s + ".mary-weight", "10");
        }
        p.setProperty("generation.symbol.10.normal-weight", "0");
        p.setProperty("generation.symbol.10.mary-weight", "0");
        return p;
    }
}
