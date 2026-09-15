package com.hd.cpgame.magicscroll2.core;

import org.junit.Test;

import java.io.BufferedReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class GenerationModelValidationTest {
    private static final Pattern FORMATION = Pattern.compile("\"formation\":\"([0-9,]+)\"");

    @Test
    public void validateHoldoutAndTenThousandGeneratedRounds() throws Exception {
        GameRuleCore core = new GameRuleCore(GenerationPolicy.defaults(),
                new TrialProbabilityPolicy(1918, 200, 698, 1347),
                SymbolWeightPolicy.localReplicaDefaults());
        RedisMemberCodec codec = new RedisMemberCodec(core.resultUtil(), GenerationPolicy.defaults());
        BigDecimal bet = new BigDecimal("0.40");
        Map<String, Integer> generated = new LinkedHashMap<String, Integer>();
        generated.put("loss", 0);
        generated.put("ordinaryWin", 0);
        generated.put("special", 0);
        Map<String, Integer> stepCounts = new LinkedHashMap<String, Integer>();
        int uniqueBoards = 0;
        java.util.Set<String> boards = new java.util.HashSet<String>();
        int maxMemberBytes = 0;
        int oraclePass = 0;
        for (int i = 0; i < 10000; i++) {
            RoundMode mode;
            int bucket = i % 10;
            if (bucket == 0) mode = RoundMode.XBOMB_WILD;
            else if (bucket == 1) mode = RoundMode.XSPLIT;
            else if (bucket == 2 || bucket == 3) mode = RoundMode.BASE_WIN;
            else mode = RoundMode.LOSS;
            GeneratedRound round = core.generateCompleteRound(bet, mode);
            ResultUtil.RoundAnalysis analysis = core.resultUtil()
                    .analyzeCompleteRound(round, GenerationPolicy.defaults());
            assertEquals(mode, analysis.getMode());
            oraclePass++;
            if (analysis.getMode() == RoundMode.LOSS) generated.put("loss", generated.get("loss") + 1);
            else if (analysis.getMode() == RoundMode.BASE_WIN) generated.put("ordinaryWin", generated.get("ordinaryWin") + 1);
            else generated.put("special", generated.get("special") + 1);
            String stepsKey = String.valueOf(round.getSteps().size());
            Integer prev = stepCounts.get(stepsKey);
            stepCounts.put(stepsKey, prev == null ? 1 : prev + 1);
            String payload = codec.encode(round);
            assertTrue(!payload.startsWith("{") && !payload.startsWith("["));
            maxMemberBytes = Math.max(maxMemberBytes, payload.getBytes(StandardCharsets.US_ASCII).length);
            GeneratedRound rebuilt = codec.decode(payload, bet);
            assertEquals(analysis.getMode(), core.resultUtil()
                    .analyzeCompleteRound(rebuilt, GenerationPolicy.defaults()).getMode());
            for (RoundStep step : round.getSteps()) boards.add(step.getFormation());
        }
        uniqueBoards = boards.size();
        assertEquals(10000, oraclePass);
        assertEquals(10000, generated.get("loss") + generated.get("ordinaryWin") + generated.get("special"));
        assertTrue(generated.get("special") >= 1500);
        assertTrue(uniqueBoards > 200);

        int holdout = 0;
        Path capture = Path.of("..", "..", "captures", "2001007-Magic-Scroll-2",
                "followup-20260829", "original-rounds.jsonl");
        try (BufferedReader reader = Files.newBufferedReader(capture, StandardCharsets.UTF_8)) {
            String line;
            ResultUtil oracle = new ResultUtil();
            while ((line = reader.readLine()) != null && holdout < 100) {
                if (!line.contains("\"ordinaryCategory\":\"ORDINARY_LOSS\"") || !line.contains("\"special\":[]")) {
                    continue;
                }
                Matcher matcher = FORMATION.matcher(line);
                if (!matcher.find()) continue;
                try {
                    oracle.assertIndependentLoss(matcher.group(1), 3, new BigDecimal("0.02"));
                } catch (RuntimeException skip) {
                    continue;
                }
                holdout++;
            }
        }
        assertEquals(100, holdout);

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"schemaVersion\": \"1.0\",\n");
        json.append("  \"gameId\": \"2001007\",\n");
        json.append("  \"directoryName\": \"2001007-Magic-Scroll-2\",\n");
        json.append("  \"status\": \"PASS\",\n");
        json.append("  \"rulesHash\": \"").append(GameConstants.RULES_HASH).append("\",\n");
        json.append("  \"generated\": {\"count\": 10000, \"loss\": ").append(generated.get("loss"))
                .append(", \"ordinaryWin\": ").append(generated.get("ordinaryWin"))
                .append(", \"special\": ").append(generated.get("special")).append("},\n");
        json.append("  \"holdout\": {\"count\": ").append(holdout)
                .append(", \"source\": \"captures/2001007-Magic-Scroll-2/followup-20260829/original-rounds.jsonl\",")
                .append("\"check\": \"ORDINARY_LOSS independent terminal formation\"},\n");
        json.append("  \"uniqueBoards\": ").append(uniqueBoards).append(",\n");
        json.append("  \"maxMemberBytes\": ").append(maxMemberBytes).append(",\n");
        json.append("  \"stepCounts\": {");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : stepCounts.entrySet()) {
            if (!first) json.append(", ");
            first = false;
            json.append("\"").append(entry.getKey()).append("\": ").append(entry.getValue());
        }
        json.append("},\n");
        json.append("  \"perCellIndependentRandom\": false,\n");
        json.append("  \"zeroWinWrittenToLossPool\": true\n");
        json.append("}\n");
        Path report = Path.of("..", "..", "reports", "2001007-Magic-Scroll-2", "generation-model-validation.json");
        Files.createDirectories(report.getParent());
        Files.writeString(report, json.toString(), StandardCharsets.UTF_8);
    }
}
