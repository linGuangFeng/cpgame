package com.cpgame.replica.hotpot;

import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotRoundKind;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class RedisLiveClaimTest {
    @Test
    void realDb15YieldsCompleteRoundAndEmptyWouldFail() throws Exception {
        Properties config = new Properties();
        config.setProperty("redis.host", "192.168.10.3");
        config.setProperty("redis.port", "6379");
        config.setProperty("redis.database", "15");
        config.setProperty("redis.game-id", "1830");
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
