package com.cpgame.sambasensation.server;

import com.cpgame.sambasensation.core.GameRuleCore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolHandoffGuardTest {
    @Test void everyConfirmedBehaviorAndUnresolvedDispositionWasReadBeforeImplementation() throws Exception {
        Path protocol = Path.of("..", "..", "protocol", "2290-Samba-Sensation", "protocol-handoff.json").normalize();
        JsonNode handoff = new ObjectMapper().readTree(Files.readString(protocol));
        assertEquals(2290, handoff.path("gameId").asInt());
        assertEquals(GameRuleCore.RULES_HASH, handoff.path("rulesHash").asText());
        assertTrue(handoff.path("implementationReady").asBoolean());
        Set<String> actual = new HashSet<>();
        for (JsonNode behavior : handoff.path("behaviorContracts")) {
            actual.add(behavior.path("id").asText());
            assertEquals("CONFIRMED", behavior.path("status").asText());
            assertFalse(behavior.path("implementationContract").path("preconditions").isEmpty());
            assertFalse(behavior.path("implementationContract").path("stateFields").isEmpty());
            assertFalse(behavior.path("implementationContract").path("transitionRules").isEmpty());
            assertFalse(behavior.path("implementationContract").path("outputs").isEmpty());
            assertFalse(behavior.path("implementationContract").path("terminationRules").isEmpty());
            assertFalse(behavior.path("verificationContract").path("usesImplementationGeneratedExpected").asBoolean(true));
            if (behavior.path("multiStep").asBoolean()) assertFalse(behavior.path("verificationContract").path("adjacentStepEvidence").isEmpty());
        }
        assertEquals(Set.copyOf(SharedRuleCoreContract.BEHAVIOR_IDS), actual);
        for (JsonNode unresolved : handoff.path("unresolvedBehaviors")) {
            assertFalse(unresolved.path("blocksGeneration").asBoolean(true));
            assertFalse(unresolved.path("disposition").asText().isBlank());
        }
    }

    @Test void controllerMainSourceDoesNotCallGenerator() throws Exception {
        Path main = Path.of("src", "main", "java");
        StringBuilder source = new StringBuilder();
        try (var files = Files.walk(main)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try { source.append(Files.readString(path)); } catch (Exception error) { throw new RuntimeException(error); }
            });
        }
        assertFalse(source.toString().contains("new CompleteRoundFactory"));
        assertFalse(source.toString().contains("generateNatural("));
        assertFalse(source.toString().contains("generateNormal("));
        assertFalse(source.toString().contains("generateSpecial("));
    }
}
