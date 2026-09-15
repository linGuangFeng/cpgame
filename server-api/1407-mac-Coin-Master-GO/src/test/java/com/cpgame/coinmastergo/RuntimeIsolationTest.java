package com.cpgame.coinmastergo;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeIsolationTest {
    @Test
    void productionJavaHasNoOracleOrHistoricalResponseRuntimeDependency() throws Exception {
        Path sourceRoot = Path.of("src", "main", "java");
        String productionSource;
        try (var files = Files.walk(sourceRoot)) {
            productionSource = files.filter(path -> path.toString().endsWith(".java"))
                    .map(path -> {
                        try { return Files.readString(path); }
                        catch (Exception error) { throw new RuntimeException(error); }
                    }).reduce("", String::concat).toLowerCase(Locale.ROOT);
        }
        assertThat(productionSource).doesNotContain("fixtures/", "fixtures\\", "captures/", "captures\\", ".jsonl");
        assertThat(productionSource).doesNotContain("resourceloader", "classpathresource");
    }
}
