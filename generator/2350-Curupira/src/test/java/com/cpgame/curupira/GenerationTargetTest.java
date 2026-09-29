package com.cpgame.curupira;

import com.cpgame.curupira.config.EngineConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenerationTargetTest {
    private Properties properties() throws Exception {
        Properties values = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("packaging/generator.properties"))) {
            values.load(reader);
        }
        return values;
    }

    @Test void usesOneUnifiedAttemptCount() throws Exception {
        EngineConfiguration config = EngineConfiguration.from(properties());
        assertEquals(100_000_000L, config.generationCount());
        assertEquals(1_000, config.batchSize());
        assertEquals(300, config.normalRetention());
        assertEquals(50, config.maryRetention());
        assertEquals(8, config.normalSceneWeight());
        assertEquals(1, config.freeExpandingWildSceneWeight());
        assertEquals(1, config.holdAndSpinsSceneWeight());
    }

    @Test void rejectsLegacyResultTargetCounts() throws Exception {
        Properties values = properties();
        values.setProperty("generation.normal-count", "100");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> EngineConfiguration.from(values));
        assertTrue(failure.getMessage().contains("generation.normal-count"));
    }

    @Test void missingOrInvalidUnifiedCountNamesTheProperty() throws Exception {
        Properties missing = properties();
        missing.remove("generation.count");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> EngineConfiguration.from(missing)).getMessage().contains("generation.count"));
        for (String invalid : new String[]{"0", "-1", "abc"}) {
            Properties values = properties();
            values.setProperty("generation.count", invalid);
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> EngineConfiguration.from(values)).getMessage().contains("generation.count"));
        }
    }
}
