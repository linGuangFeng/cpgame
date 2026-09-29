package com.cpgame.curupira;

import com.cpgame.curupira.config.EngineConfiguration;
import com.cpgame.curupira.loader.GenerationRun;
import com.cpgame.curupira.model.CompleteRoundFact.Kind;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenerationRunTest {
    @Test void attemptsAreNotReplenishedAndTailBatchKeepsPhaseCycle() throws Exception {
        Properties properties = properties();
        properties.setProperty("generation.count", "23");
        properties.setProperty("generation.batch-size", "2");
        EngineConfiguration config = EngineConfiguration.from(properties);
        List<Integer> phases = new ArrayList<>();
        List<Integer> batchSizes = new ArrayList<>();
        List<String> members = new ArrayList<>();
        GenerationRun.Summary summary = GenerationRun.execute(config, new DeterministicRandomSource(235023L),
                (batch, phase, entries) -> {
                    phases.add(phase);
                    batchSizes.add(entries.size());
                    entries.forEach(entry -> members.add(entry.member()));
                });

        assertEquals(23, summary.attempts());
        assertEquals(12, summary.batches());
        assertEquals(summary.attempts(), summary.accepted() + summary.rejected());
        assertEquals(List.of(0,1,2,3,4,5,6,7,8,9,10,0), phases);
        assertEquals(3L, summary.phaseAttempts().get(0));
        for (int phase = 1; phase <= 10; phase++) assertEquals(2L, summary.phaseAttempts().get(phase));
        assertEquals(summary.accepted(), batchSizes.stream().mapToLong(Integer::longValue).sum());
        assertTrue(members.stream().noneMatch(member -> member.contains("#")));
    }

    @Test void eachBoostPhaseStartsFromBaseAndBoostsOnlyOneSymbol() throws Exception {
        EngineConfiguration config = EngineConfiguration.from(properties());
        var base = config.effectiveWeights(0);
        for (int phase = 1; phase <= config.weightOrder().size(); phase++) {
            var effective = config.effectiveWeights(phase);
            int boosted = config.weightOrder().get(phase - 1);
            for (int symbol : config.weightOrder()) {
                int expected = symbol == boosted
                        ? Math.multiplyExact(base.get(symbol), config.boostFactors().get(symbol))
                        : base.get(symbol);
                assertEquals(expected, effective.get(symbol), "phase=" + phase + " symbol=" + symbol);
            }
        }
        assertEquals(base, config.effectiveWeights(config.cycleLength() - 1).entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        java.util.Map.Entry::getKey,
                        entry -> entry.getKey().equals(config.weightOrder().getLast())
                                ? entry.getValue() / config.boostFactors().get(entry.getKey())
                                : entry.getValue())));
    }

    @Test void unifiedAttemptBudgetGeneratesBothMaryFamiliesWithoutTargetTopUp() throws Exception {
        Properties properties = properties();
        properties.setProperty("generation.count", "3000");
        EngineConfiguration config = EngineConfiguration.from(properties);
        List<String> keys = new ArrayList<>();
        GenerationRun.Summary summary = GenerationRun.execute(config, new DeterministicRandomSource(235024L),
                (batch, phase, entries) -> entries.forEach(entry -> keys.add(entry.listKey())));

        assertTrue(summary.acceptedByKind().getOrDefault(Kind.FREE_EW, 0L) > 0);
        assertTrue(summary.acceptedByKind().getOrDefault(Kind.HOLD, 0L) > 0);
        assertTrue(keys.stream().anyMatch(key -> key.startsWith("MaryLog:0")));
        assertTrue(keys.stream().anyMatch(key -> key.startsWith("MaryLog:1")));
        assertEquals(3000, summary.attempts());
        assertEquals(summary.attempts(), summary.accepted() + summary.rejected());
    }

    private static Properties properties() throws Exception {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of("packaging/generator.properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
