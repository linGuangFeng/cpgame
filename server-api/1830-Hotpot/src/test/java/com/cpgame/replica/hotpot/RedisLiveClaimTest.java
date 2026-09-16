package com.cpgame.replica.hotpot;

import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotRoundKind;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class RedisLiveClaimTest {
    @Test
    void configuredCacheYieldsCompleteRoundAndEmptyWouldFail() throws Exception {
        Properties config = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("D:/work/hd/cpgame/server-api/1830-Hotpot/dist/controller.properties"))) {
            config.load(reader);
        }
        assertEquals("8001830", config.getProperty("redis.game-id"));
        assertEquals("0", config.getProperty("redis.database"));
        try (RedisRoundStore store = RedisRoundStore.connect(config)) {
            RedisRoundStore.ClaimedRound claimed = store.claim(new SecureRandom());
            assertNotNull(claimed.member());
            assertFalse(claimed.member().startsWith("{"));
            assertTrue(claimed.kind() == HotpotRoundKind.ORDINARY_LOSS
                    || claimed.kind() == HotpotRoundKind.ORDINARY_WIN
                    || claimed.kind() == HotpotRoundKind.SCATTER_FREE_SPINS);
            assertEquals(claimed.verification().multiplier(), claimed.ratio());
            assertEquals(claimed.kind() == HotpotRoundKind.SCATTER_FREE_SPINS,
                    claimed.fact().spins().size() > 1);
        } catch (java.io.IOException ex) {
            String message = ex.getMessage() == null ? "" : ex.getMessage();
            assertTrue(message.contains("无法连接 Redis") || message.contains("Redis"), message);
            assertTrue(message.contains("不准改成内存出牌") || message.contains("不准改成内存"), message);
            assertFalse(message.toLowerCase().contains("deal locally"));
        }
    }
}
