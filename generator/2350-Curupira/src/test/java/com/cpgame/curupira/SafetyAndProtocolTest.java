package com.cpgame.curupira;

import com.cpgame.curupira.codec.MinimalFactCodec;
import com.cpgame.curupira.config.EngineConfiguration;
import com.cpgame.curupira.core.GameRules;
import com.cpgame.curupira.core.RulesContract;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SafetyAndProtocolTest {
    private static final Path FORMAL = Path.of("packaging/generator.properties");

    @Test void formalConfigurationIsExplicitAndUsesNoResultTargets() throws Exception {
        EngineConfiguration config = EngineConfiguration.load(FORMAL);
        assertEquals(30_000, config.redisConnectTimeoutMs());
        assertEquals(GameRules.SYMBOL_ORDER, config.weightOrder());
        assertTrue(config.boostFactors().values().stream().allMatch(value -> value == 3));
        assertTrue(config.maryRetention() > 0);
        assertTrue(config.freeExpandingWildSceneWeight() > 0);
        assertTrue(config.holdAndSpinsSceneWeight() > 0);
        String text = Files.readString(FORMAL).toLowerCase();
        assertFalse(text.contains("seed"));
        assertFalse(text.contains("generation.normal-count"));
        assertFalse(text.contains("generation.special-count"));
        assertFalse(text.contains("redis.namespace"));
        assertFalse(text.contains("cu1pl;#"));
    }

    @Test void unsupportedKeysFailBeforeGeneration() throws Exception {
        Properties modeProperties = properties();
        modeProperties.setProperty("modes.HOLD_AND_SPINS.enabled", "true");
        assertThrows(IllegalArgumentException.class, () -> EngineConfiguration.from(modeProperties));
        Properties targetProperties = properties();
        targetProperties.setProperty("generation.loss.target", "100");
        assertThrows(IllegalArgumentException.class, () -> EngineConfiguration.from(targetProperties));
    }

    @Test void acceptedHandoffHasSameHashAndAllBehaviors() throws Exception {
        Path root = Path.of("../../protocol/2350-Curupira");
        String capabilities = Files.readString(root.resolve("game-capabilities.json"), StandardCharsets.UTF_8);
        String handoff = Files.readString(root.resolve("protocol-handoff.json"), StandardCharsets.UTF_8);
        assertTrue(capabilities.contains(RulesContract.RULES_HASH));
        assertTrue(handoff.contains(RulesContract.RULES_HASH));
        for (String id : RulesContract.BEHAVIOR_IDS) assertTrue(handoff.contains(id), id);
    }

    @Test void productionHasNoFixtureOrCapturedRoundDependency() throws Exception {
        try (var paths = Files.walk(Path.of("src/main"))) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                String source = Files.readString(file).toLowerCase();
                assertFalse(source.contains("fixture"), file.toString());
                assertFalse(source.contains("spin-index"), file.toString());
                assertFalse(source.contains("paid-rounds"), file.toString());
                assertFalse(source.contains("demo-script"), file.toString());
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new MinimalFactCodec().decode("CU1PL;#"));
    }

    private static Properties properties() throws Exception {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(FORMAL, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
