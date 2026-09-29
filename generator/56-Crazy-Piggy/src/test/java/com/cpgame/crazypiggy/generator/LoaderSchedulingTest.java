package com.cpgame.crazypiggy.generator;

import com.cpgame.crazypiggy.generator.model.RoundResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LoaderSchedulingTest {
    @Test
    @Timeout(180)
    void unifiedPoolCoversDirectAndBoosterWithoutExceedingSharedCapacity() throws Exception {
        Properties p = config();
        p.setProperty("generation.max-members-per-multiplier", "12");
        GeneratorConfig loaded = GeneratorConfig.load(write(p));
        Map<Integer, Set<String>> seen = new HashMap<>();
        Map<Integer, Map<String, Integer>> families = new HashMap<>();
        var summary = EnumerationLoader.generate(loaded, (round, analysis) -> {
            int ratio = analysis.totalAward().divide(analysis.betAmount()).intValueExact();
            assertTrue(loaded.outputLimits.accepts(false, ratio));
            assertTrue(seen.computeIfAbsent(ratio, k -> new HashSet<>()).add(identity(round)));
            families.computeIfAbsent(ratio, k -> new HashMap<>()).merge(family(round), 1, Integer::sum);
        });
        assertEquals(0, summary.win() + summary.loss() - seen.values().stream().mapToInt(Set::size).sum());
        assertTrue(summary.booster() > 0);
        assertTrue(seen.keySet().stream().anyMatch(n -> n >= 25));
        assertTrue(seen.values().stream().allMatch(s -> s.size() <= 12));
        assertTrue(families.values().stream().anyMatch(m -> m.containsKey("DIRECT") && m.containsKey("BOOSTER")));
        assertEquals(summary.loss() + summary.win(), seen.values().stream().mapToInt(Set::size).sum());
    }

    @Test
    @Timeout(180)
    void normalRangeAppliesToAllFeaturesAndRetiredCountsAreIgnored() throws Exception {
        Properties p = config();
        p.setProperty("generation.normal-min-win-multiplier", "5");
        p.setProperty("generation.normal-max-win-multiplier", "5");
        p.setProperty("generation.max-members-per-multiplier", "8");
        p.setProperty("generation.loss-count", "1");
        p.setProperty("generation.win-count", "1");
        p.setProperty("generation.special-count", "1");
        p.setProperty("generation.special-min-win-multiplier", "25");
        Set<String> families = new HashSet<>();
        var summary = EnumerationLoader.generate(GeneratorConfig.load(write(p)), (round, analysis) -> {
            assertEquals(0, analysis.totalAward().divide(analysis.betAmount()).intValueExact() - 5);
            families.add(family(round));
        });
        assertEquals(8, summary.loss() + summary.win());
        assertTrue(families.contains("DIRECT"));
        assertTrue(families.contains("BOOSTER"));
        assertTrue(summary.win() > 1);
    }

    @Test
    @Timeout(180)
    void unreachableRangeTerminates() throws Exception {
        Properties p = config();
        p.setProperty("generation.normal-min-win-multiplier", "1999");
        p.setProperty("generation.normal-max-win-multiplier", "1999");
        assertEquals(0, EnumerationLoader.generate(GeneratorConfig.load(write(p)), (round, analysis) -> fail()).win());
    }

    @Test
    @Timeout(180)
    void zeroRangeFillsOrdinaryLossBucket() throws Exception {
        Properties p = config();
        p.setProperty("generation.normal-min-win-multiplier", "0");
        p.setProperty("generation.normal-max-win-multiplier", "0");
        p.setProperty("generation.max-members-per-multiplier", "3");
        var summary = EnumerationLoader.generate(GeneratorConfig.load(write(p)),
                (round, analysis) -> assertEquals(0, analysis.totalAward().signum()));
        assertEquals(3, summary.loss());
        assertEquals(0, summary.win());
    }

    private static String family(RoundResult round) {
        if (round.boosterWheel()) return "BOOSTER";
        if (round.loss()) return "LOSS";
        return "DIRECT";
    }

    private static String identity(RoundResult round) {
        return round.symbols() + "|" + round.wheelPositions() + "|" + round.wheelMultipliers();
    }

    private static Properties config() throws Exception {
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("dist", "generator.properties"), StandardCharsets.UTF_8)) {
            p.load(reader);
        }
        return p;
    }

    private static Path write(Properties p) throws Exception {
        Path file = Files.createTempFile("crazy-piggy-enum-", ".properties");
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) { p.store(writer, "test"); }
        file.toFile().deleteOnExit();
        return file;
    }
}
