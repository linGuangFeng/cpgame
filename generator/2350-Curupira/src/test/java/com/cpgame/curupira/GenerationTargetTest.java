package com.cpgame.curupira;

import com.cpgame.curupira.config.EngineConfiguration;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class GenerationTargetTest {
    private Properties properties(String normal, String special) throws Exception {
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("packaging/generator.properties"))) { p.load(reader); }
        p.setProperty("generation.normal-count", normal);
        p.setProperty("generation.special-count", special);
        return p;
    }
    @Test void acceptsCurrentProductionTarget() throws Exception {
        var c = EngineConfiguration.from(properties("300000000", "20000000"));
        assertEquals(300000000, c.normalCount());
        assertEquals(20000000, c.specialCount());
    }
    @Test void combinedTargetDoesNotOverflow() throws Exception {
        var c = EngineConfiguration.from(properties("1500000000", "1500000000"));
        assertEquals(3000000000L, (long)c.normalCount() + c.specialCount());
    }
    @Test void rejectsInvalidTargetsWithPropertyName() throws Exception {
        for (String value : new String[]{"-1", "2147483648", "abc"}) {
            Properties p = properties(value, "20000000");
            var error = assertThrows(IllegalArgumentException.class, () -> EngineConfiguration.from(p));
            assertTrue(error.getMessage().contains("generation.normal-count"));
            assertTrue(error.getMessage().contains(value));
        }
        Properties zero = properties("0", "0");
        assertThrows(IllegalArgumentException.class, () -> EngineConfiguration.from(zero));
    }
}
