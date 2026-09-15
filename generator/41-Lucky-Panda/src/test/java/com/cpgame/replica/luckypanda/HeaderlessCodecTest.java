package com.cpgame.replica.luckypanda;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HeaderlessCodecTest {
    @Test void removesOnlyEnvelopeAndKeepsLegacyReadable() {
        var codec = new CompleteRoundCodec();
        var random = new Random(41915);
        var factory = new CompleteRoundFactory(new BigDecimal("0.02"), 3);
        int accepted = 0;
        for (int i = 0; i < 120; i++) {
            CompleteRoundFactory.GeneratedRound generated;
            try {
                generated = factory.generate(random, 10, 30,
                        i % 2 == 0 ? CompleteRoundFactoryTest.weights()
                                : RedisDirectLoader.boostedScatter(CompleteRoundFactoryTest.weights()), false);
            } catch (CompleteRoundFactory.RoundRejectedException ignored) { continue; }
            var fact = generated.fact();
            String encoded = codec.encode(fact);
            assertFalse(encoded.contains("="));
            assertFalse(encoded.contains(","));
            assertFalse(encoded.startsWith("lp1|"));
            var decoded = codec.decode(encoded);
            assertEquals(generated.actualMultiplier(), codec.verify(decoded, 10).actualMultiplier());
            var legacy = codec.decode(codec.encodeFull(fact));
            assertEquals(fact.betSize(), legacy.betSize());
            assertEquals(fact.betLevel(), legacy.betLevel());
            assertEquals(codec.encodeFull(fact), codec.encodeFull(legacy));
            assertEquals(encoded, codec.encode(decoded));
            var originalSpins = new java.util.ArrayList<java.util.List<CompleteRoundFact.PageFact>>();
            originalSpins.add(fact.paid()); originalSpins.addAll(fact.freeSpins());
            var decodedSpins = new java.util.ArrayList<java.util.List<CompleteRoundFact.PageFact>>();
            decodedSpins.add(decoded.paid()); decodedSpins.addAll(decoded.freeSpins());
            String[] segments = encoded.split("\\|", -1);
            for (int spin = 0; spin < segments.length; spin++) {
                if (segments[spin].startsWith("#")) continue;
                var before = originalSpins.get(spin); var after = decodedSpins.get(spin);
                assertEquals(before.size(), after.size());
                for (int page = 0; page < before.size(); page++) {
                    assertEquals(before.get(page).board().toRskl(), after.get(page).board().toRskl());
                    assertEquals(before.get(page).rpx(), after.get(page).rpx());
                    assertEquals(before.get(page).gfl(), after.get(page).gfl());
                    assertEquals(before.get(page).sfl(), after.get(page).sfl());
                }
            }
            // The old envelope may also contain markers from the previous loader.
            String oldMarkers = "lp1|bs=0.02|bl=3|P=" + encoded.replace("|", "|F=");
            assertEquals(generated.actualMultiplier(), codec.verify(oldMarkers, 10).actualMultiplier());
            accepted++;
        }
        assertTrue(accepted >= 50);
        for (String invalid : List.of("#0|", "|#0", "#0||#1", "bs=1|bl=1|P=#0"))
            assertThrows(IllegalArgumentException.class, () -> codec.decode(invalid));
    }
}
