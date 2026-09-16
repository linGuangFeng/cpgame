package com.cpgame.replica.luckypanda.api;

import com.cpgame.replica.luckypanda.CompleteRoundCodec;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.GameRuleCore;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.RoundClass;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CascadeAndFreeWalkTest {
    @Test
    void ordinaryWinWalksEveryTumbleWithoutSecondClaim() throws Exception {
        FakeRedisCommands fake = new FakeRedisCommands();
        CompleteRoundCodec codec = new CompleteRoundCodec();
        var generated = TestMembers.generate(RoundClass.ORDINARY_WIN, new Random(41), 8000);
        fake.seed(false, generated.actualMultiplier(), codec.encode(generated.fact()));
        RedisRoundStore store = new RedisRoundStore(fake, 41L);
        CountingRandom random = new CountingRandom(true);
        LuckyPandaService service = new LuckyPandaService(store, new BigDecimal("1000.00"), random);
        Map<String, Object> auth = service.auth(Map.of("gid", "41", "t", "win-walk"), null);
        assertEquals(200, auth.get("code"));
        int steps = 0;
        int ss = 0;
        BigDecimal lastRwa = BigDecimal.ZERO;
        do {
            Map<String, Object> spin = service.spin(Map.of("gid", "41", "t", "win-walk", "bs", "0.02", "bl", "1"),
                    "step-" + steps);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) spin.get("data");
            ss = ((Number) data.get("ss")).intValue();
            lastRwa = (BigDecimal) data.get("rwa");
            steps++;
            if (steps == 1) {
                assertEquals(0, ((Number) data.get("nfsc")).intValue());
                assertTrue(data.get("ba").toString().startsWith("0.4"));
            } else {
                assertEquals("0.00", data.get("ba").toString());
            }
        } while (ss == 0 && steps < 40);
        assertTrue(steps >= 2);
        assertEquals(1, ss);
        assertTrue(lastRwa.signum() > 0);
        Map<String, Object> history = service.historyList(Map.of("gid", "41", "t", "win-walk", "page_index", "1"));
        @SuppressWarnings("unchecked")
        Map<String, Object> hist = (Map<String, Object>) history.get("data");
        assertEquals(1, hist.get("lc"));
        assertEquals(1L, fake.command("LLEN", "BetLog:000000041:"
                + String.format("%06d", generated.actualMultiplier())));
    }

    @Test
    void scatterFreeWalksTenFreeSpinsFromOneMember() throws Exception {
        FakeRedisCommands fake = new FakeRedisCommands();
        CompleteRoundCodec codec = new CompleteRoundCodec();
        var generated = TestMembers.generate(RoundClass.SCATTER_FREE, new Random(91), 12000);
        fake.seed(true, generated.actualMultiplier(), codec.encode(generated.fact()));
        RedisRoundStore store = new RedisRoundStore(fake, 41L);
        LuckyPandaService service = new LuckyPandaService(store, new BigDecimal("1000.00"), new AlwaysWinRandom());
        service.auth(Map.of("gid", "41", "t", "free-walk"), null);
        int steps = 0;
        int maxNfsc = 0;
        int fsn = 0;
        int ss = 0;
        boolean sawTriggerPage = false;
        do {
            Map<String, Object> spin = service.spin(Map.of("gid", "41", "t", "free-walk", "bs", "0.02", "bl", "1"),
                    null);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) spin.get("data");
            ss = ((Number) data.get("ss")).intValue();
            fsn = ((Number) data.get("fsn")).intValue();
            int nfsc = ((Number) data.get("nfsc")).intValue();
            maxNfsc = Math.max(maxNfsc, nfsc);
            if (fsn >= GameRuleCore.FREE_SPINS && nfsc == 0 && ss == 1) {
                sawTriggerPage = true;
                assertEquals(0, ((BigDecimal) data.get("wa")).compareTo(BigDecimal.ZERO.setScale(2)));
            }
            steps++;
        } while (!(ss == 1 && fsn == maxNfsc && maxNfsc >= 10) && steps < 200);
        assertTrue(sawTriggerPage);
        assertTrue(maxNfsc >= 10);
        assertEquals(maxNfsc, fsn);
        assertEquals(0, fsn % 2);
        assertEquals(1, ss);
        Map<String, Object> history = service.historyDetail(Map.of(
                "gid", "41", "t", "free-walk",
                "transfer_id", transferId(service)));
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) history.get("data");
        assertTrue(detail.containsKey("fsl"));
        assertTrue(((java.util.List<?>) detail.get("fsl")).size() >= 10);
    }

    @SuppressWarnings("unchecked")
    private static String transferId(LuckyPandaService service) {
        Map<String, Object> history = service.historyList(Map.of("gid", "41", "t", "free-walk", "page_index", "1"));
        Map<String, Object> data = (Map<String, Object>) history.get("data");
        Map<String, Object> row = (Map<String, Object>) ((java.util.List<?>) data.get("ll")).get(0);
        return row.get("tis").toString();
    }

    private static final class CountingRandom extends SecureRandom {
        private final boolean win;
        CountingRandom(boolean win) { this.win = win; }
        @Override public boolean nextBoolean() { return win; }
        @Override public int nextInt(int bound) { return bound - 1; }
    }

    private static final class AlwaysWinRandom extends SecureRandom {
        @Override public boolean nextBoolean() { return true; }
        @Override public int nextInt(int bound) { return bound - 1; }
    }
}
