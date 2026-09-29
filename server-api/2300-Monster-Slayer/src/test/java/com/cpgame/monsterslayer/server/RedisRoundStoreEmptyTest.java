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

    @Test
    void firstPaidClickUsesWinPoolWhenConfiguredMinimumExcludesZero() throws Exception {
        var round = new com.cpgame.monsterslayer.core.GameRuleCore.CompleteRound(false,
                List.of(new com.cpgame.monsterslayer.core.GameRuleCore.Step(
                        new int[]{1,2,3,1,4,5,1,6,7,8,9,10,8,9,10}, 0, 0)));
        int m = com.cpgame.monsterslayer.core.ResultUtil.redisMultiplierCenti(round);
        assertTrue(m > 0);
        String member = new com.cpgame.monsterslayer.core.MinimalRoundFactCodec().encode(round);
        RedisCommands redis = new RedisCommands() {
            public Object command(String... args) {
                return switch(args[0]) {
                    case "ZRANGE" -> List.of(Integer.toString(m));
                    case "LLEN" -> args[1].endsWith(":000000") ? 0L : 1L;
                    case "LINDEX" -> member;
                    default -> throw new AssertionError(args[0]);
                };
            }
            public void close() {}
        };
        var forceLossCoin = new java.util.Random(0) {
            @Override public boolean nextBoolean() { return false; }
        };
        try (var store = new RedisRoundStore(redis, forceLossCoin)) {
            var service = new MonsterSlayerService(store, new java.math.BigDecimal("10000"));
            var response = service.spin(java.util.Map.of("gid","2300","token","positive-min-first-click","type","1","bet","0.2","level","10"));
            org.junit.jupiter.api.Assertions.assertEquals(0, response.path("code").asInt());
            assertTrue(response.path("data").path("tw").decimalValue().signum() > 0);
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
