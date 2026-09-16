package com.cpgame.replica.luckypanda.api;
import com.cpgame.replica.luckypanda.*;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.RoundClass;
import org.junit.jupiter.api.Test;
import java.security.SecureRandom;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TargetFloorLookupTest {
    @Test void skipsOnlyEmptyLowerBucketsAndNeverUsesAnAboveTargetBucket() throws Exception {
        var codec = new CompleteRoundCodec();
        var original = TestMembers.generate(RoundClass.ORDINARY_WIN, new Random(41), 8000).fact();
        var plain = new CompleteRoundFact(original.betSize(), original.betLevel(), original.paid().stream()
                .map(p -> new CompleteRoundFact.PageFact(p.board(), 1, p.gfl(), p.sfl())).toList(), List.of());
        int base = codec.verify(plain, 10).actualMultiplier();
        var fake = new FakeRedisCommands();
        fake.seed(false, base, codec.encode(plain));
        fake.seed(false, base * 2, "stale bucket");
        fake.command("LPOP", "BetLog:000000041:" + String.format("%06d", base * 2));
        fake.seed(false, base * 3, "above target must never be read");
        fake.calls.clear();
        var store = new RedisRoundStore(fake, 41);
        assertEquals(base, store.atOrBelow(false, base * 3 - 1, new SecureRandom()).ratio());
        var floors = fake.calls.stream().filter(c -> c.get(0).equals("ZREVRANGEBYSCORE")).toList();
        assertEquals(2, floors.size());
        assertEquals("(" + base * 2, floors.get(1).get(2));
        assertTrue(fake.calls.stream().noneMatch(c -> c.get(0).equals("ZRANGE")));
        assertNull(store.atOrBelow(false, base - 1, new SecureRandom()));
    }
    @Test void targetUsesPoolMaximumAndOnlySelectedBucketIsReadWithThousandsOfBuckets() throws Exception {
        var win = TestMembers.generate(RoundClass.ORDINARY_WIN, new Random(41), 8000);
        var fake = new FakeRedisCommands();
        for (int i = 1; i <= 3000; i++) {
            fake.seed(false, i, "unselected entries must not be inspected");
        }
        fake.command("LPOP", "BetLog:000000041:" + String.format("%06d", win.actualMultiplier()));
        fake.seed(false, win.actualMultiplier(), new CompleteRoundCodec().encode(win.fact()));
        fake.calls.clear();
        var random = new SecureRandom() {
            int booleans;
            @Override public boolean nextBoolean() { return booleans++ == 0; }
            @Override public int nextInt(int bound) { assertEquals(3000, bound); return win.actualMultiplier() - 1; }
        };
        assertEquals(win.actualMultiplier(), new RedisRoundStore(fake, 41).claim(random).ratio());
        assertEquals(List.of("ZREVRANGE", "ZREVRANGEBYSCORE", "LLEN", "LINDEX"),
                fake.calls.stream().map(c -> c.get(0)).toList());
    }
    @Test void randomMemberOffsetIsUsedAndCacheIsNotConsumed() throws Exception {
        var fake = new FakeRedisCommands();
        fake.seed(false, 0, "#0"); fake.seed(false, 0, "#1");
        var random = new SecureRandom() {
            @Override public boolean nextBoolean() { return false; }
            @Override public long nextLong(long bound) { assertEquals(2L, bound); return 1; }
        };
        assertEquals("#1", new RedisRoundStore(fake, 41).claim(random).member());
        assertEquals(2L, fake.command("LLEN", "BetLog:000000041:000000"));
        assertTrue(fake.calls.contains(List.of("LINDEX", "BetLog:000000041:000000", "1")));
    }
}
