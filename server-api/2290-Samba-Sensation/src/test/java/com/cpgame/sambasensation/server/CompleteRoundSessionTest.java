package com.cpgame.sambasensation.server;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CompleteRoundSessionTest {
    @Test void naturalFreeWalksFiveContinuationsToZeroThenAnotherPaidRoundStarts() throws Exception {
        FakeRedisCommands redis = new FakeRedisCommands();
        String idle = TestRoundMembers.loss(1), free = TestRoundMembers.naturalFreeWithPaidWin();
        redis.seed(false, 0, idle);
        redis.seed(true, TestRoundMembers.multiplier(free), free);
        SambaSensationService service = new SambaSensationService(new RedisRoundStore(redis, 2290),
                new BigDecimal("10000"), new ScriptedRandom(true, false));
        Map<String, String> paid = form("round-a", "1");
        JsonNode start = service.spin(paid, "paid-1");
        assertEquals(5, start.path("data").path("frees").path("st").asInt());
        assertEquals(1, start.path("data").path("type").asInt());
        assertFalse(start.path("data").has("roundKey"));
        assertFalse(start.path("data").has("deliveryIndex"));
        assertFalse(start.path("data").has("deliveryCount"));
        assertFalse(start.path("data").has("_source"));
        assertEquals(0, start.path("data").path("total_win").decimalValue()
                .compareTo(start.path("data").path("frees").path("twa").decimalValue()));
        assertEquals(0, start.path("data").path("odds").decimalValue()
                .compareTo(start.path("data").path("frees").path("m").decimalValue()));
        JsonNode duplicate = service.spin(paid, "paid-1");
        assertEquals(start, duplicate);
        BigDecimal cumulative = start.path("data").path("total_win").decimalValue();
        int[] expected = {4, 3, 2, 1, 0};
        for (int i = 0; i < expected.length; i++) {
            JsonNode step = service.spin(form("round-a", "2"), "free-" + i);
            assertEquals(expected[i], step.path("data").path("frees").path("st").asInt());
            assertEquals(2, step.path("data").path("type").asInt());
            assertEquals(new BigDecimal("5.00"), step.path("data").path("bet_gold").decimalValue());
            cumulative = cumulative.add(step.path("data").path("total_win").decimalValue());
            assertEquals(0, cumulative.compareTo(step.path("data").path("frees").path("twa").decimalValue()));
        }
        assertThrows(IllegalArgumentException.class, () -> service.spin(form("round-a", "2"), "after-terminal"));

        JsonNode next = service.spin(form("round-a", "1"), "paid-2");
        assertEquals(0, next.path("data").path("total_win").decimalValue().signum());
        JsonNode detail = service.historyDetail(Map.of("token", "round-a", "page", "1", "page_size", "30"));
        assertEquals(2, detail.path("data").path("list").size());
        boolean sixSteps = false;
        for (JsonNode row : detail.path("data").path("list")) if (row.path("results").size() == 6) sixSteps = true;
        assertTrue(sixSteps);
    }

    @Test void featureBuyClaimsBuyMemberAndReturnsExactTerminalFreeState() throws Exception {
        FakeRedisCommands redis = new FakeRedisCommands();
        String idle = TestRoundMembers.loss(1), buy = TestRoundMembers.featureBuy();
        redis.seed(false, 0, idle);
        redis.seed(true, TestRoundMembers.multiplier(buy), buy);
        SambaSensationService service = new SambaSensationService(new RedisRoundStore(redis, 2290),
                new BigDecimal("10000"), new ScriptedRandom(true));
        JsonNode start = service.spin(form("buyer", "3"), "buy-start");
        assertEquals(3, start.path("data").path("type").asInt());
        assertEquals(5, start.path("data").path("frees").path("st").asInt());
        assertEquals(25, start.path("data").path("props").path("scatter").asInt(),
                "feature-buy protocol cursor must match the provider response");
        assertEquals(new BigDecimal("400.00"), start.path("data").path("bet_gold").decimalValue());
        JsonNode terminal = null;
        for (int i = 0; i < 5; i++) {
            terminal = service.spin(form("buyer", "2"), "buy-free-" + i);
            assertEquals(25, terminal.path("data").path("props").path("scatter").asInt());
            assertEquals(new BigDecimal("5.00"), terminal.path("data").path("bet_gold").decimalValue());
        }
        assertNotNull(terminal);
        assertEquals(0, terminal.path("data").path("frees").path("st").asInt());
        assertEquals(6, service.historyDetail(Map.of("token", "buyer", "page", "1", "page_size", "30"))
                .path("data").path("list").get(0).path("results").size());
    }

    @Test void paidRoundsCarryScatterAndCoinStateThenResetOnlyAfterFullReward() throws Exception {
        FakeRedisCommands redis = new FakeRedisCommands();
        String inc0 = TestRoundMembers.incrementLoss(0, true);
        String inc1 = TestRoundMembers.incrementLoss(1, false);
        String inc2 = TestRoundMembers.incrementLoss(2, false);
        String inc3 = TestRoundMembers.incrementLoss(3, false);
        String invalidFinalOrdinary = TestRoundMembers.incrementLoss(4, false);
        String unchanged = TestRoundMembers.loss(1);
        String reward = TestRoundMembers.coinRewardFive();
        redis.seed(false, 0, inc0, inc1, inc2, inc3, invalidFinalOrdinary, unchanged);
        redis.seed(true, TestRoundMembers.multiplier(reward), reward);
        SambaSensationService service = new SambaSensationService(new RedisRoundStore(redis, 2290),
                new BigDecimal("10000"), new ScriptedRandom(false, false, false, false, true, false).offsets(0, 1, 2, 3, 0, 5));
        for (int i = 0; i < 4; i++) {
            JsonNode paid = service.spin(form("cross-round", "1"), "cross-" + i);
            assertEquals(i + 1, paid.path("data").path("props").path("coins").path("count").asInt());
            assertFalse(paid.path("data").path("props").path("coins").path("is_full").asBoolean());
        }
        JsonNode beforeReward = service.status(Map.of("token", "cross-round"));
        assertEquals(1, beforeReward.path("scatterProgress").asInt());
        JsonNode full = service.spin(formWithBetType("cross-round", "1", "3"), "cross-full");
        assertTrue(full.path("data").path("props").path("coins").path("is_full").asBoolean());
        assertEquals(5, full.path("data").path("props").path("coins").path("count").asInt());
        JsonNode reset = service.spin(form("cross-round", "1"), "cross-reset");
        assertFalse(reset.path("data").path("props").path("coins").path("is_full").asBoolean());
        assertEquals(0, reset.path("data").path("props").path("coins").path("count").asInt());
        assertEquals(1, reset.path("data").path("props").path("scatter").asInt());
    }

    @Test void ordinaryPaidPathUsesSecureRandomWinLossChoiceWithoutClientResultSelection() throws Exception {
        FakeRedisCommands redis = new FakeRedisCommands();
        String loss = TestRoundMembers.loss(1), win = TestRoundMembers.win(1);
        redis.seed(false, 0, loss);
        redis.seed(false, TestRoundMembers.multiplier(win), win);
        SambaSensationService service = new SambaSensationService(new RedisRoundStore(redis, 2290),
                new BigDecimal("10000"), new ScriptedRandom(false, true));
        JsonNode first = service.spin(form("ordinary-random", "1"), "ordinary-1");
        JsonNode second = service.spin(form("ordinary-random", "1"), "ordinary-2");
        assertEquals(0, first.path("data").path("total_win").decimalValue().signum());
        assertTrue(second.path("data").path("total_win").decimalValue().signum() > 0);
    }

    @Test void originalPageCanOmitContinuationTypeOnlyWhileCompleteFreeRoundIsActive() throws Exception {
        FakeRedisCommands redis = new FakeRedisCommands();
        String idle = TestRoundMembers.loss(1);
        String natural = TestRoundMembers.naturalFree();
        redis.seed(false, 0, idle, idle);
        redis.seed(true, TestRoundMembers.multiplier(natural), natural);
        SambaSensationService service = new SambaSensationService(new RedisRoundStore(redis, 2290),
                new BigDecimal("10000"), new ScriptedRandom(true));
        JsonNode terminal = service.spin(form("natural-page", "1"), "natural-start");
        for (int step = 0; step < 5; step++) {
            Map<String, String> continuation = form("natural-page", "2");
            continuation.remove("type");
            terminal = service.spin(continuation, "natural-free-" + step);
        }
        assertEquals(0, terminal.path("data").path("frees").path("st").asInt());
        Map<String, String> noTypePaid = form("natural-page", "1");
        noTypePaid.remove("type");
        assertEquals(0, service.spin(noTypePaid, "next-paid").path("code").asInt());
    }

    private static Map<String, String> form(String token, String type) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("token", token); values.put("gid", "2290"); values.put("bet", "0.02");
        values.put("level", "10"); values.put("bet_type", "1"); values.put("type", type); return values;
    }
    private static Map<String, String> formWithBetType(String token, String type, String betType) {
        Map<String, String> values = form(token, type); values.put("bet_type", betType); return values;
    }

    private static final class ScriptedRandom extends SecureRandom {
        private final ArrayDeque<Boolean> booleans = new ArrayDeque<>();
        private final ArrayDeque<Long> offsets = new ArrayDeque<>();
        ScriptedRandom offsets(long... values) { for (long value : values) offsets.add(value); return this; }
        ScriptedRandom(boolean... values) { for (boolean value : values) booleans.add(value); }
        @Override public boolean nextBoolean() { return booleans.isEmpty() ? false : booleans.removeFirst(); }
        @Override public int nextInt(int bound) { return 0; }
        @Override public long nextLong(long origin, long bound) { return bound - 1; }
        @Override public long nextLong(long bound) { return offsets.isEmpty() ? 0 : Math.floorMod(offsets.removeFirst(), bound); }
    }
}
