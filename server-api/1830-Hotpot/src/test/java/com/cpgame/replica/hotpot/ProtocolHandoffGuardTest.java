package com.cpgame.replica.hotpot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolHandoffGuardTest {
    private static final Path HANDOFF = Path.of("D:/work/hd/cpgame/protocol/1830-Hotpot/protocol-handoff.json");
    private static final Path CAPABILITIES = Path.of("D:/work/hd/cpgame/protocol/1830-Hotpot/game-capabilities.json");

    @Test
    void rulesHashAndBehaviorsMatchHandoff() throws Exception {
        ObjectMapper json = new ObjectMapper();
        JsonNode handoff = json.readTree(Files.readAllBytes(HANDOFF));
        JsonNode capabilities = json.readTree(Files.readAllBytes(CAPABILITIES));
        String hash = "01123dc898992e5e38efb1f58b816c844093dfcf07939e2cefa3153022b61c01";
        assertEquals(hash, handoff.path("rulesHash").asText());
        assertEquals(hash, capabilities.path("handoffContract").path("rulesHash").asText());
        assertEquals(hash, HotpotRulesMetadata.PROTOCOL_HASH);
        Set<String> ids = new HashSet<>();
        for (JsonNode behavior : handoff.path("behaviorContracts")) {
            assertEquals("CONFIRMED", behavior.path("status").asText(), behavior.path("id").asText());
            ids.add(behavior.path("id").asText());
            if (behavior.path("multiStep").asBoolean()) {
                assertTrue(behavior.path("verificationContract").path("adjacentStepEvidence").isArray());
                assertTrue(behavior.path("verificationContract").path("adjacentStepEvidence").size() > 0,
                        behavior.path("id").asText());
            }
            assertFalse(behavior.path("verificationContract").path("usesImplementationGeneratedExpected").asBoolean());
        }
        assertEquals(19, ids.size());
        assertTrue(ids.contains("B-FEATURE-BUY-ABSENT"));
        assertTrue(ids.contains("B-SCATTER-FREE-SPINS"));
        assertTrue(ids.contains("B-NO-WILD-SUBSTITUTE"));
        for (JsonNode unresolved : handoff.path("unresolvedBehaviors")) {
            String id = unresolved.path("id").asText();
            assertTrue(id.equals("U-SYMBOL-WEIGHTS") || id.equals("U-INTEGER-MULTIPLIER-RATE"));
        }
    }
}
