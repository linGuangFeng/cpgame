package com.cpgame.curupira;

import com.cpgame.curupira.core.RulesContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolGateTest {
    private static final Path PROTOCOL = Path.of("../../protocol/2350-Curupira");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptedArtifactsHaveOneRulesHashAndAllBehaviorContracts() throws Exception {
        JsonNode capabilities = read("game-capabilities.json");
        JsonNode spec = read("protocol-spec.json");
        JsonNode handoff = read("protocol-handoff.json");

        assertThat(capabilities.path("rulesHash").asText()).isEqualTo(RulesContract.RULES_HASH);
        assertThat(spec.path("rulesHash").asText()).isEqualTo(RulesContract.RULES_HASH);
        assertThat(handoff.path("rulesHash").asText()).isEqualTo(RulesContract.RULES_HASH);
        assertThat(capabilities.path("rulesVersion").asText()).isEqualTo(RulesContract.RULES_VERSION);

        Set<String> behaviorIds = new HashSet<>();
        for (JsonNode behavior : handoff.path("behaviorContracts")) {
            String id = behavior.path("id").asText();
            behaviorIds.add(id);
            assertThat(behavior.path("verificationContract").path("usesImplementationGeneratedExpected").asBoolean())
                    .as(id).isFalse();
            if (behavior.path("status").asText().equals("CONFIRMED")) {
                JsonNode contract = behavior.path("implementationContract");
                assertThat(contract.path("preconditions").isArray()).as(id).isTrue();
                assertThat(contract.path("stateFields").isArray()).as(id).isTrue();
                assertThat(contract.path("transitionRules").isArray()).as(id).isTrue();
                assertThat(contract.path("outputs").isArray()).as(id).isTrue();
                assertThat(contract.path("terminationRules").isArray()).as(id).isTrue();
            }
        }
        assertThat(behaviorIds).containsExactlyInAnyOrderElementsOf(RulesContract.BEHAVIOR_IDS);
        assertThat(RulesContract.UNSUPPORTED_UNKNOWN_BEHAVIORS).isEmpty();
    }

    private JsonNode read(String name) throws Exception {
        Path path = PROTOCOL.resolve(name);
        assertThat(Files.isRegularFile(path)).isTrue();
        return mapper.readTree(path.toFile());
    }
}
