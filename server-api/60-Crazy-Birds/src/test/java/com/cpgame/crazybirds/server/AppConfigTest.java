package com.cpgame.crazybirds.server;

import com.cpgame.crazybirds.generator.GameRules;
import com.cpgame.crazybirds.generator.RedisLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AppConfigTest {
    @Test
    void handlerKeepsExistingPerAndMaryFamilies() {
        long gameId = 8_000_060L;

        assertEquals("PerKeyList_008000060", RedisLoader.normalIndex(gameId, GameRules.NORMAL_POOL_TYPE));
        assertEquals("BetLog:008000060:000025",
                RedisLoader.normalList(gameId, GameRules.NORMAL_POOL_TYPE, 25));
        assertEquals("MaryKeyList_008000060",
                RedisLoader.maryIndex(gameId, GameRules.FREE_SPINS_MARY_POOL_TYPE));
        assertEquals("MaryLog:008000060:000500",
                RedisLoader.maryList(gameId, GameRules.FREE_SPINS_MARY_POOL_TYPE, 500));
    }

    @Test
    void deliveryConfigUsesNewSceneAndSixBandWeights() throws Exception {
        AppConfig config = AppConfig.load(Path.of("dist", "controller.properties"), 50060, null);

        assertEquals(300, config.normalSceneWeight());
        assertEquals(30, config.freeSpinsSceneWeight());
        assertEquals(List.of(200, 40, 25, 20, 10, 5), config.normalBands().weights());
        assertEquals(List.of(0, 2, 4, 6, 7, 10), config.freeSpinsBands().weights());
        assertEquals(6, config.normalBands().weights().size());
        assertEquals(6, config.freeSpinsBands().weights().size());
    }

    @Test
    void cacheBandsUseScale100AndDoNotOverlap() {
        assertEquals(0, MultiplierBand.ZERO.minimum());
        assertEquals(0, MultiplierBand.ZERO.maximum());
        assertEquals(1, MultiplierBand.UP_TO_5.minimum());
        assertEquals(500, MultiplierBand.UP_TO_5.maximum());
        assertEquals(501, MultiplierBand.FIVE_TO_20.minimum());
        assertEquals(2_000, MultiplierBand.FIVE_TO_20.maximum());
        assertEquals(10_001, MultiplierBand.HUNDRED_TO_10000.minimum());
        assertEquals(1_000_000, MultiplierBand.HUNDRED_TO_10000.maximum());
    }
}
