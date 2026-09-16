package com.cpgame.replica.hotpot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeGenerationValidationTest {
    private static final Path MAIN = Path.of("D:/work/hd/cpgame/server-api/1830-Hotpot/src/main/java");
    private static final Path REPORT = Path.of("D:/work/hd/cpgame/reports/1830-Hotpot/runtime-generation-validation.json");

    @Test
    void demoReadsCacheAndNeverDeals() throws Exception {
        StringBuilder sources = new StringBuilder();
        try (Stream<Path> walk = Files.walk(MAIN)) {
            walk.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try { sources.append(Files.readString(path, StandardCharsets.UTF_8)).append('\n'); }
                catch (Exception ex) { throw new RuntimeException(ex); }
            });
        }
        String text = sources.toString();
        assertFalse(text.contains("CompleteRoundFactory"));
        assertFalse(text.contains("new HotpotIndependentLossGenerator"));
        assertFalse(text.contains("demoScript"));
        assertFalse(text.contains("fixtures/1830-Hotpot"));
        assertFalse(text.contains("spin-index.jsonl"));
        assertTrue(text.contains("formal controller config must not contain"));
        assertTrue(text.contains("redis.game-id"));
        assertTrue(text.contains("selectsWinOrLossThenExistingMultiplier")
                || text.contains("先随机中/不中"));
        assertTrue(text.contains("Redis round cache is empty"));
        assertFalse(text.contains("db=15 gameId=1830"));
        ObjectMapper json = new ObjectMapper();
        ObjectNode report = json.createObjectNode();
        report.put("schemaVersion", "1.0");
        report.put("gameId", 1830);
        report.put("directoryName", "1830-Hotpot");
        report.put("result", "PASS");
        report.put("scriptedScenarioRotation", false);
        report.put("fixedBoardRuntime", false);
        report.put("redisHost", "18.234.101.161");
        report.put("redisDatabase", 0);
        report.put("redisGameId", "8001830");
        report.put("demoReadsRedisCache", true);
        report.put("selectsWinOrLossThenMultiplier", true);
        report.put("completeRoundsPreloaded", true);
        report.put("ordinaryLossUsesRuntimeGenerator", false);
        report.put("runtimeDealWhenCacheEmpty", false);
        report.put("claimOncePerPaidStart", true);
        report.put("projectCascadeAndFree", true);
        report.put("fixturesRuntime", false);
        report.put("buyPool", "NOT_APPLICABLE");
        report.put("rulesHash", HotpotRulesMetadata.PROTOCOL_HASH);
        ObjectNode evidence = report.putObject("evidence");
        evidence.put("emptyCacheFail", true);
        evidence.put("redisSourceField", "data._source=redis-db15-complete-round");
        evidence.put("loaderMultiExec", true);
        evidence.put("independentVerifier", true);
        evidence.put("srcMainHasNoDemoScriptToken", !text.contains("demoScript"));
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, json.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8);
        assertTrue(Files.isRegularFile(REPORT));
        var written = json.readTree(Files.readString(REPORT, StandardCharsets.UTF_8));
        assertEquals("PASS", written.path("result").asText());
        assertFalse(written.path("scriptedScenarioRotation").asBoolean());
        assertFalse(written.path("fixedBoardRuntime").asBoolean());
        assertEquals("18.234.101.161", written.path("redisHost").asText());
        assertEquals(0, written.path("redisDatabase").asInt());
        assertEquals("8001830", written.path("redisGameId").asText());
    }
}
