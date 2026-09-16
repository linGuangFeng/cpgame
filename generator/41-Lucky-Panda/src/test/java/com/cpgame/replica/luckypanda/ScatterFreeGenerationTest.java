package com.cpgame.replica.luckypanda;

import com.hd.pg.appapi.business.vo.cpgame.luckypanda.RoundClass;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScatterFreeGenerationTest {
    @Test
    void boostedEntryCanProduceScatterFreeCompleteRound() {
        CompleteRoundFactory factory = new CompleteRoundFactory(new BigDecimal("0.02"), 1);
        CompleteRoundCodec codec = new CompleteRoundCodec();
        var weights = CompleteRoundFactoryTest.weights();
        Random random = new Random(41041);
        int found = 0;
        for (int i = 0; i < 400 && found == 0; i++) {
            CompleteRoundFactory.GeneratedRound generated;
            try {
                generated = factory.generate(random, 10, 30, weights, false, true);
            } catch (CompleteRoundFactory.RoundRejectedException rejected) {
                continue;
            }
            RoundVerification verification = codec.verify(codec.encode(generated.fact()), 10);
            if (verification.roundClass() == RoundClass.SCATTER_FREE) {
                found++;
                assertTrue(verification.freeSpins() >= 10);
                assertEquals(0, verification.freeSpins() % 2);
                assertTrue(verification.special());
                assertTrue(verification.paidPages() >= 1);
            }
        }
        assertTrue(found >= 1, "boosted scatter entry should produce at least one SCATTER_FREE round");
    }
}
