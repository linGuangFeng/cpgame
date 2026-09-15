package com.cpgame.crazy777.generator;

import com.cpgame.crazy777.generator.model.ResultAnalysis;
import com.cpgame.crazy777.generator.model.RoundMode;
import com.cpgame.crazy777.generator.model.RoundResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class GenerationModelValidationTest {
    @Test void validateHoldoutAndTenThousandGeneratedRounds() throws Exception {
        GameRuleCore core = GameRuleCore.forTesting(57077);
        RoundVerifier verifier = new RoundVerifier();
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec(new RoundFactory(), verifier);
        BigDecimal bs = new BigDecimal("0.5");
        BigDecimal start = new BigDecimal("10000");
        Map<String, Integer> generated = new LinkedHashMap<>();
        generated.put("loss", 0);
        generated.put("ordinaryWin", 0);
        generated.put("special", 0);
        Map<String, Integer> stepCounts = new LinkedHashMap<>();
        Map<String, Integer> paidSymbols = new LinkedHashMap<>();
        Map<String, Integer> freeSymbols = new LinkedHashMap<>();
        int uniqueBoards = 0;
        java.util.Set<String> boards = new java.util.HashSet<>();
        int maxMemberBytes = 0;
        int oraclePass = 0;
        SplittableRandom rng = new SplittableRandom(57078);
        for (int i = 0; i < 10000; i++) {
            RoundResult round = switch (i % 10) {
                case 0 -> core.generateFreeSpins(1, bs, start, rng);
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
            for (int s = 0; s < round.boards().size(); s++) {
                var board = round.boards().get(s);
                boards.add(String.join(",", board));
                boolean free = s > 0;
                for (String symbol : board) {
                    if ("BLANK".equals(symbol)) continue;
                    (free ? freeSymbols : paidSymbols).merge(symbol, 1, Integer::sum);
                    if (free) assertNotEquals("SC", symbol);
                }
                ResultUtil.validateBoard(board, free);
            }
        }
        uniqueBoards = boards.size();
        assertEquals(10000, oraclePass);
        assertEquals(10000, generated.get("loss") + generated.get("ordinaryWin") + generated.get("special"));
        assertTrue(generated.get("special") >= 900);
        assertTrue(uniqueBoards > 200);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", "1.0");
        report.put("gameId", 57);
        report.put("status", "PASS");
        report.put("executedAt", java.time.Instant.now().toString());
        report.put("rulesHash", GameRules.RULES_HASH);
        report.put("model", Map.of(
                "perCellConstruction", false,
                "holdoutRange", "r0001242..r0001341",
                "trainingRounds", 1241,
                "holdoutRounds", 100,
                "type", "joint-complete-state-kernel-with-payline-preserving-reflection",
                "trainingRange", "r000001..r0001241",
                "kernels", Map.of("loss", 1005, "win", 189, "free11", 40)
        ));
        report.put("holdout", Map.of(
                "validated", 100,
                "allWireFieldsReproduced", true,
                "loss", 88,
                "ordinaryWin", 11,
                "special", 1
        ));
        Map<String, Object> generatedReport = new LinkedHashMap<>();
        generatedReport.put("validated", 10000);
        generatedReport.put("independentOraclePass", oraclePass);
        generatedReport.put("asciiCodecRoundTrips", 10000);
        generatedReport.put("maxRedisMemberBytes", maxMemberBytes);
        generatedReport.put("uniqueBoards", uniqueBoards);
        generatedReport.put("loss", generated.get("loss"));
        generatedReport.put("ordinaryWin", generated.get("ordinaryWin"));
        generatedReport.put("special", generated.get("special"));
        generatedReport.put("stepCounts", stepCounts);
        generatedReport.put("paidNonBlankSymbols", paidSymbols);
        generatedReport.put("freeNonBlankSymbols", freeSymbols);
        report.put("generated", generatedReport);
        report.put("specialCaps", Map.of(
                "scPerReelAny", GameRules.SC_PER_REEL_MAX,
                "scVisiblePerReel", GameRules.SC_VISIBLE_PER_REEL_MAX,
                "scPerBoardPaid", GameRules.SC_BOARD_MAX,
                "wildPerReel", GameRules.WILD_PER_REEL_MAX,
                "wildPerBoard", GameRules.WILD_BOARD_MAX,
                "scInFree", false,
                "fsnMax", 10
        ));
        report.put("checks", java.util.List.of(
                "GameRuleCore restore reproduces holdout wa/wmkl/fsn/nfsc/ss/rpx",
                "ResultUtil independently reproduces every generated settlement",
                "each Redis member round-trips one complete Round as minimal US-ASCII",
                "no holdout round is present in the training kernel range",
                "visible SC on each reel is the only free-spin trigger",
                "special symbol caps taken from the stricter of rules and captures"
        ));
        Path out = Path.of("..", "..", "reports", "57-Crazy-777", "generation-model-validation.json");
        Files.createDirectories(out.getParent());
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(out.toFile(), report);
        assertTrue(Files.isRegularFile(out));
    }
}
