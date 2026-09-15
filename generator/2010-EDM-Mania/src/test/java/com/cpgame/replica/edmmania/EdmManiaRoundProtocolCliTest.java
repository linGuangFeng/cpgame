package com.cpgame.replica.edmmania;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hd.pg.appapi.business.vo.cpgame.edmmania.EdmManiaBoardGenerator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class EdmManiaRoundProtocolCliTest {
    @Test void emitsProtocolFromTheSameCompleteRoundCore() throws Exception {
        CompleteRoundFactory.GeneratedRound generated = null;
        Random seeded = new Random(2010L);
        for (int i = 0; i < 50_000 && generated == null; i++) {
            try {
                CompleteRoundFactory.GeneratedRound candidate = new CompleteRoundFactory().generate(seeded, false, 12, 30,
                        EdmManiaBoardGenerator.defaultNormalWeights(), EdmManiaBoardGenerator.defaultFreeWeights(), true, true);
                if (candidate.fact().spins().size() > 1) generated = candidate;
            } catch (CompleteRoundFactory.RoundRejectedException ignored) { }
        }
        assertNotNull(generated, "special opening must produce a free-feature round");
        JsonNode spins = new ObjectMapper().readTree(EdmManiaRoundProtocolCli.protocol(
                generated.fact(), new BigDecimal("0.02"), 10, 2010L));
        assertTrue(spins.isArray()); assertTrue(spins.size() > 1);
        assertEquals(3, spins.get(0).path("type").asInt());
        assertEquals(2, spins.get(1).path("type").asInt());
        assertEquals(spins.get(0).path("_awarded_free_spins").asInt(), spins.get(0).path("_free_total").asInt(),
                "trigger tt is the award on that spin, not the final retriggered total");
    }

    @Test void featureBuyRoundEmitsType3AndFeatureBuyFlag() throws Exception {
        CompleteRoundFactory.GeneratedRound generated = null;
        Random seeded = new Random(20102010L);
        for (int i = 0; i < 5_000 && generated == null; i++) {
            try {
                generated = new CompleteRoundFactory().generate(seeded, true, 12, 30);
            } catch (CompleteRoundFactory.RoundRejectedException ignored) { }
        }
        assertNotNull(generated);
        assertTrue(generated.fact().featureBuy());
        assertTrue(generated.fact().spins().size() > 1);
        JsonNode spins = new ObjectMapper().readTree(EdmManiaRoundProtocolCli.protocol(
                generated.fact(), new BigDecimal("0.02"), 10, 2010L));
        assertEquals(3, spins.get(0).path("type").asInt());
        assertTrue(spins.get(0).path("_feature_buy").asBoolean());
        assertEquals(2, spins.get(1).path("type").asInt());
        assertEquals(EdmManiaRulesMetadata.VERSION, spins.get(0).path("_rulesVersion").asText());
        assertEquals(EdmManiaRulesMetadata.HASH, spins.get(0).path("_rulesHash").asText());
        assertEquals(2010L, spins.get(0).path("_seed").asLong());
        boolean foundWin = false;
        for (JsonNode spin : spins) {
            assertTrue(spin.path("props").size() >= 1);
            for (JsonNode page : spin.path("props")) {
                for (JsonNode win : page.path("win_arr")) {
                    foundWin = true;
                    for (JsonNode position : win.path("p")) {
                        assertTrue(position.isArray(), "main win positions must be nested arrays for the client animation");
                        assertTrue(position.size() >= 1);
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
        Random random = new Random(20102010L);
        int accepted = 0;
        int rejected = 0;
        for (int i = 0; i < 2_000; i++) {
            try {
                CompleteRoundFactory.GeneratedRound round = factory.generate(random, false, 1);
                RoundVerification verification = codec.verify(codec.encode(round.fact()), 1, false);
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
                () -> new CompleteRoundFactory().generate(new Random(2010L), true, 10, 1));
    }

}
