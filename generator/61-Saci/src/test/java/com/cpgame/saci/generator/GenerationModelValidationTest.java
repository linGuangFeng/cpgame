package com.cpgame.saci.generator;

import com.cpgame.saci.generator.model.ResultAnalysis;
import com.cpgame.saci.generator.model.RoundMode;
import com.cpgame.saci.generator.model.RoundResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationModelValidationTest {
    @Test void validateHoldoutAndTenThousandGeneratedRounds() throws Exception {
        GameRuleCore core = GameRuleCore.forTesting(61077);
        RoundVerifier verifier = new RoundVerifier();
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec(new RoundFactory(), verifier);
        BigDecimal bs = new BigDecimal("0.02");
        BigDecimal start = new BigDecimal("10000");
        Map<String, Integer> generated = new LinkedHashMap<>();
        generated.put("loss", 0);
        generated.put("ordinaryWin", 0);
        generated.put("special", 0);
        Map<String, Integer> stepCounts = new LinkedHashMap<>();
        Map<String, Integer> paidSymbols = new LinkedHashMap<>();
        int uniqueBoards = 0;
        java.util.Set<String> boards = new java.util.HashSet<>();
        int maxMemberBytes = 0;
        int oraclePass = 0;
        SplittableRandom rng = new SplittableRandom(61078);
        for (int i = 0; i < 10000; i++) {
            RoundResult round = switch (i % 10) {
                case 0 -> core.generateSpecial(1, bs, start, rng);
                case 1, 2 -> core.generateOrdinaryWin(1, bs, start, rng);
                default -> core.generateIndependentLoss(1, bs, start, rng);
            };
            ResultAnalysis analysis = verifier.verify(round);
            oraclePass++;
            if (analysis.mode() == RoundMode.ORDINARY_LOSS) generated.merge("loss", 1, Integer::sum);
            else if (analysis.mode() == RoundMode.ORDINARY_WIN) generated.merge("ordinaryWin", 1, Integer::sum);
            else generated.merge("special", 1, Integer::sum);
            stepCounts.merge(String.valueOf(round.steps().size()), 1, Integer::sum);
            String payload = codec.encodeRedisMemberString(round);
            maxMemberBytes = Math.max(maxMemberBytes, payload.getBytes(java.nio.charset.StandardCharsets.US_ASCII).length);
            codec.decodeRedisMember(payload, bs, 1, start);
            var first = round.steps().get(0).rskl();
            boards.add(String.join(",", first));
            for (String symbol : first) {
                paidSymbols.merge(String.valueOf(symbol.charAt(1)), 1, Integer::sum);
            }
            ResultUtil.validateBoard(first, true);
        }
        uniqueBoards = boards.size();
        assertEquals(10000, oraclePass);
        assertEquals(10000, generated.get("loss") + generated.get("ordinaryWin") + generated.get("special"));
        assertTrue(generated.get("special") >= 900);
        assertTrue(uniqueBoards > 50);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", "1.0");
        report.put("gameId", 61);
        report.put("status", "PASS");
        report.put("executedAt", java.time.Instant.now().toString());
        report.put("rulesHash", GameRules.RULES_HASH);
        report.put("model", Map.of(
                "perCellConstruction", false,
                "holdoutKernels", 100,
                "trainingKernels", core.trainingKernelCount(),
                "sampling", "whole-complete-round-kernels",
                "entries", Map.of(
                        "paidFirstBoard", Map.of("sampleCells", 25185, "source", "training paid first rskl"),
                        "cascadeAndFeature", Map.of("note", "后续 Step 随完整局 kernel 一并抽样，不单独补牌")
                )));
        report.put("generated10000", Map.of(
                "oraclePass", oraclePass,
                "categories", generated,
                "stepCounts", stepCounts,
                "uniqueFirstBoards", uniqueBoards,
                "maxMemberBytes", maxMemberBytes,
                "paidSymbols", paidSymbols));
        Path out = Path.of("..", "..", "reports", "61-Saci", "generation-model-validation.json");
        if (!Files.isDirectory(out.getParent())) {
            out = Path.of("reports", "61-Saci", "generation-model-validation.json");
        }
        Files.createDirectories(out.getParent());
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(out.toFile(), report);
        assertTrue(Files.isRegularFile(out));
    }
}
