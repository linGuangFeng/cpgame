package com.cpgame.replica.freedomday;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class FreedomDayRoundProtocolCliTest {
    @Test void emitsProtocolFromTheSameCompleteRoundCore() throws Exception {
        CompleteRoundFactory.GeneratedRound generated = new CompleteRoundFactory().generate(new Random(2260L), true, 10);
        JsonNode spins = new ObjectMapper().readTree(FreedomDayRoundProtocolCli.protocol(
                generated.fact(), new BigDecimal("0.01"), 1, 2260L));
        assertTrue(spins.isArray()); assertTrue(spins.size() > 1);
        assertEquals(3, spins.get(0).path("type").asInt());
        assertEquals(FreedomDayRulesMetadata.VERSION, spins.get(0).path("_rulesVersion").asText());
        assertEquals(FreedomDayRulesMetadata.HASH, spins.get(0).path("_rulesHash").asText());
        assertEquals(2260L, spins.get(0).path("_seed").asLong());
        boolean foundWin = false;
        for (JsonNode spin : spins) {
            assertTrue(spin.path("props").size() >= 1);
            for (JsonNode page : spin.path("props")) {
                for (JsonNode win : page.path("win_arr")) {
                    foundWin = true;
                    for (JsonNode position : win.path("p")) {
                        assertTrue(position.isArray(), "main win positions must be nested arrays for the client animation");
                        assertFalse(position.isEmpty());
                        assertTrue(position.size() <= 4, "a visible symbol/frame occupies at most four cells");
                    }
                }
            }
            JsonNode terminal = spin.path("props").get(spin.path("props").size() - 1);
            assertEquals(0, terminal.path("win_arr").size());
        }
        assertTrue(foundWin, "test round must include at least one win");
    }

    @Test void overlongNaturalCascadesAreRejectedInsteadOfKillingGeneration() {
        CompleteRoundFactory factory = new CompleteRoundFactory();
        CompleteRoundCodec codec = new CompleteRoundCodec();
        Random random = new Random(18092260L);
        int accepted = 0;
        int rejected = 0;
        for (int i = 0; i < 20_000; i++) {
            try {
                CompleteRoundFactory.GeneratedRound round = factory.generate(random, i % 5 == 0, 1);
                RoundVerification verification = codec.verify(codec.encode(round.fact()), 1, i % 5 == 0);
                assertTrue(verification.maxConsecutiveWins() <= 1);
                accepted++;
            } catch (CompleteRoundFactory.RoundRejectedException expected) {
                rejected++;
            }
        }
        assertTrue(accepted > 0, "natural terminal rounds must still be accepted");
        assertTrue(rejected > 0, "continued wins at the cap must discard the whole round");
    }

    @Test void rejectsTheWholeRoundWhenMarySpinLimitIsExceeded() {
        assertThrows(CompleteRoundFactory.RoundRejectedException.class,
                () -> new CompleteRoundFactory().generate(new Random(2260L), true, 10, 1));
    }

}
