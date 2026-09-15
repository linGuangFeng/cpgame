package com.cpgame.crazy777.generator;

import com.cpgame.crazy777.generator.model.RoundFacts;
import com.cpgame.crazy777.generator.model.RoundResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CaptureOracleTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RoundFactory factory = new RoundFactory();
    private final RoundVerifier verifier = new RoundVerifier();

    @Test void holdoutOneHundredRoundsRestoreFromOriginalBoards() throws Exception {
        var in = CaptureOracleTest.class.getResourceAsStream("/holdout-100.jsonl");
        assertNotNull(in);
        int restored = 0;
        int skippedAggregate = 0;
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode round = JSON.readTree(line);
                int steps = round.path("stepCount").asInt();
                if ("FREE_SPINS".equals(round.path("categories").get(0).asText()) && steps == 1) {
                    skippedAggregate++;
                    continue;
                }
                restoreAndCompare(round);
                restored++;
            }
        }
        assertEquals(100, restored + skippedAggregate);
        assertTrue(restored >= 99);
    }

    @Test void allCapturedCompleteRoundsMatchIndependentOracle() throws Exception {
        Path file = Path.of("..", "..", "captures", "57-Crazy-777", "rounds.jsonl");
        if (!Files.isRegularFile(file)) file = Path.of("captures", "57-Crazy-777", "rounds.jsonl");
        assertTrue(Files.isRegularFile(file), "缺少原厂完整局 rounds.jsonl");
        int restored = 0;
        int skipped = 0;
        try (var reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode round = JSON.readTree(line);
                int steps = round.path("stepCount").asInt();
                if ("FREE_SPINS".equals(round.path("categories").get(0).asText()) && steps == 1) {
                    skipped++;
                    continue;
                }
                restoreAndCompare(round);
                restored++;
            }
        }
        assertEquals(1341, restored + skipped);
        assertEquals(7, skipped);
        assertEquals(1334, restored);
    }

    private void restoreAndCompare(JsonNode round) {
        JsonNode steps = round.path("steps");
        List<List<String>> boards = new ArrayList<>();
        for (JsonNode step : steps) {
            List<String> board = new ArrayList<>();
            for (JsonNode symbol : step.path("result").path("rskl")) board.add(symbol.asText());
            boards.add(board);
        }
        JsonNode first = steps.get(0).path("result");
        int bl = first.path("bl").asInt();
        BigDecimal bs = new BigDecimal(first.path("bs").asText());
        BigDecimal ba = new BigDecimal(first.path("ba").asText());
        BigDecimal firstPb = new BigDecimal(first.path("pb").asText());
        BigDecimal firstWa = new BigDecimal(first.path("wa").asText());
        BigDecimal start = firstPb.add(ba).subtract(firstWa);
        RoundFacts facts = new RoundFacts(round.path("roundId").asText(), bs, bl, boards);
        RoundResult restored = factory.restore(facts, start);
        verifier.verify(restored);
        assertEquals(steps.size(), restored.steps().size());
        for (int i = 0; i < steps.size(); i++) {
            JsonNode expected = steps.get(i).path("result");
            var actual = restored.steps().get(i);
            assertEquals(expected.path("wa").decimalValue().stripTrailingZeros(),
                    actual.wa().stripTrailingZeros(), round.path("roundId").asText() + " wa[" + i + "]");
            assertEquals(wmkl(expected.path("wmkl")), actual.wmkl(),
                    round.path("roundId").asText() + " wmkl[" + i + "]");
            assertEquals(expected.path("fsn").asInt(), actual.fsn());
            assertEquals(expected.path("nfsc").asInt(), actual.nfsc());
            assertEquals(expected.path("ss").asInt(), actual.ss());
            assertEquals(expected.path("rpx").asInt(), actual.rpx());
            assertEquals(expected.path("gt").asInt(), actual.gt());
            assertEquals(expected.path("small_game_type").asInt(), actual.smallGameType());
        }
    }

    private static java.util.Map<String, String> wmkl(JsonNode node) {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue().asText()));
        }
        return result;
    }
}
