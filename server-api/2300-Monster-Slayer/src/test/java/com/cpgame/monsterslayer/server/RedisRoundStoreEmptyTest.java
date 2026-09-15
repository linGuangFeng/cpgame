package com.cpgame.monsterslayer.server;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RedisRoundStoreEmptyTest {
    @Test
    void emptyRedisFailsExplicitlyWithoutRuntimeFallback() throws Exception {
        try (RedisRoundStore store = new RedisRoundStore(new EmptyRedis())) {
            IllegalStateException peek = assertThrows(IllegalStateException.class, store::peekLoss);
            IllegalStateException claim = assertThrows(
                    IllegalStateException.class,
                    () -> store.claimBuy(3));
            assertTrue(peek.getMessage().contains("Redis round cache unavailable/empty"));
            assertTrue(claim.getMessage().contains("Redis round cache unavailable/empty"));
        }
    }

    @Test
    void emptyBuyPoolFailsWithoutOrdinaryFallback() throws Exception {
        RedisCommands redis = new RedisCommands() {
            public Object command(String... args) {
                if (args[0].equals("ZRANGE")) return List.of();
                if (args[0].equals("LLEN")) return 0L;
                return List.of();
            }
            public void close() {}
        };
        try (RedisRoundStore store = new RedisRoundStore(redis)) {
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> store.claimBuy(3));
            assertTrue(error.getMessage().contains("empty"));
        }
    }

    private static final class EmptyRedis implements RedisCommands {
        @Override
        public Object command(String... args) {
            if ("LINDEX".equals(args[0]) || "LPOP".equals(args[0])) return null;
            if ("LLEN".equals(args[0])) return 0L;
            return List.of();
        }

        @Override
        public void close() throws IOException {
        }
    }
}
