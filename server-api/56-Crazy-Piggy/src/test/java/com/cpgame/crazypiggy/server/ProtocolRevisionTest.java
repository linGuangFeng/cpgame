package com.cpgame.crazypiggy.server;

import com.cpgame.crazypiggy.generator.GameRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolRevisionTest {
    private static final List<String> EXPECTED_BEHAVIORS = List.of(
            "B-ENTRY-IDENTITY", "B-LANGUAGE-FIRST-LOAD", "B-HTTP-CODEC", "B-SESSION-INIT",
            "B-CONFIG-RESTORE", "B-ORDINARY-LOSS", "B-ORDINARY-WIN", "B-FIXED-PAYLINE-WIN",
            "B-BALANCE-ACCOUNTING", "B-BOOSTER-WHEEL", "B-RULES-PAYTABLE-LAZY",
            "B-AUTH-REJECTION", "B-HISTORY-READ", "B-REDIS-COMPLETE-ROUND");

    @Test void implementationIsLockedToAcceptedHandoff() throws Exception {
        Path protocol = Path.of("../../protocol/56-Crazy-Piggy").toAbsolutePath().normalize();
        ObjectMapper json = new ObjectMapper();
        JsonNode capabilities = json.readTree(protocol.resolve("game-capabilities.json").toFile());
        JsonNode spec = json.readTree(protocol.resolve("protocol-spec.json").toFile());
        JsonNode handoff = json.readTree(protocol.resolve("protocol-handoff.json").toFile());
        assertEquals(GameRules.RULES_HASH, capabilities.path("rulesHash").asText());
        assertEquals(GameRules.RULES_HASH, capabilities.path("handoffContract").path("rulesHash").asText());
        assertEquals(GameRules.RULES_HASH, spec.path("rulesHash").asText());
        assertEquals(GameRules.RULES_HASH, handoff.path("rulesHash").asText());
        assertEquals(EXPECTED_BEHAVIORS, textValues(capabilities.path("handoffContract").path("behaviorIds"), false));
        assertEquals(EXPECTED_BEHAVIORS, textValues(handoff.path("behaviorContracts"), true));
        assertEquals(textValues(spec.path("behaviorIds"), false), textValues(capabilities.path("behaviorIds"), false));
        assertEquals(spec.path("stateDefinitions"), capabilities.path("stateDefinitions"));
        assertEquals(spec.path("stateDefinitions"), handoff.path("stateDefinitions"));
        for (JsonNode behavior : handoff.path("behaviorContracts")) {
            String id = behavior.path("id").asText();
            String status = behavior.path("status").asText();
            if (id.equals("B-HISTORY-READ")) {
                assertEquals("UNKNOWN", status);
                assertFalse(behavior.path("reason").asText().isBlank());
            } else {
                assertEquals("CONFIRMED", status, id);
            }
            assertFalse(behavior.path("implementationContract").path("transitionRules").isEmpty(), id);
            assertFalse(behavior.path("verificationContract").path("assertions").isEmpty(), id);
            assertFalse(behavior.path("verificationContract").path("usesImplementationGeneratedExpected").asBoolean());
            if (behavior.path("multiStep").asBoolean()) {
                assertFalse(behavior.path("verificationContract").path("adjacentStepEvidence").isEmpty(), id);
            }
        }
    }

    private static List<String> textValues(JsonNode array, boolean useId) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : array) values.add(useId ? item.path("id").asText() : item.asText());
        return values;
    }
}
