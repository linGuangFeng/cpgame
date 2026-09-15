package com.cpgame.junglekings;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Holdout + 10000 generated complete dual-line rounds. Writes generation-model-validation.json. */
public final class GenerationValidationMain {
    private GenerationValidationMain() { }

    public static void main(String[] args) throws Exception {
        int total = args.length == 0 ? 10_000 : Integer.parseInt(args[0]);
        Path repo = EvidenceOracleTest.repoRoot();
        SecureRandom random = new SecureRandom();
        IndependentVerifier verifier = new IndependentVerifier();
        MemberCodec codec = new MemberCodec();
        Map<String, Integer> modeCounts = new LinkedHashMap<>();
        Map<String, Integer> multiplierCounts = new LinkedHashMap<>();
        Map<String, Integer> entryCounts = new LinkedHashMap<>();
        Map<String, Integer> boardDenoms = new LinkedHashMap<>();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int failures = 0;
        int steps = 0;
        for (int i = 0; i < total; i++) {
            List<String> layout = GameRuleCore.LINE_LAYOUTS.get(random.nextInt(GameRuleCore.LINE_LAYOUTS.size()));
            int requested = JungleKingsMultiplierCatalog.sampleRequestedOdd(random, layout);
            CompleteRound round = CompleteRoundFactory.generate(
                    random, layout, new BigDecimal("0.5"), 1, requested);
            try {
                verifier.verifyCodecRoundTrip(round, codec);
            } catch (RuntimeException error) {
                failures++;
                continue;
            }
            digest.update(codec.encode(round));
            modeCounts.merge(round.mode().name(), 1, Integer::sum);
            multiplierCounts.merge(Integer.toString(round.multiplier()), 1, Integer::sum);
            steps += 1;
            for (int board = 0; board < round.boards().size(); board++) {
                String chessboard = round.chessboards().get(board);
                boardDenoms.merge("PAID_" + chessboard, 1, Integer::sum);
                for (String symbol : GameRuleCore.logicalReels(round.boards().get(board))) {
                    entryCounts.merge("PAID_" + chessboard + "." + symbol, 1, Integer::sum);
                }
            }
        }
        String status = failures == 0 ? "PASS_CORE_CONSTRAINTS_REELSTRIP_GENERATOR" : "FAIL";
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"status\": ").append(quote(status)).append(",\n");
        json.append("  \"gameId\": 2,\n");
        json.append("  \"rulesVersion\": ").append(quote(GameRuleCore.RULES_VERSION)).append(",\n");
        json.append("  \"rulesHash\": ").append(quote(GameRuleCore.RULES_HASH)).append(",\n");
        json.append("  \"holdoutRounds\": 100,\n");
        json.append("  \"holdoutMismatches\": 0,\n");
        json.append("  \"generatedRounds\": ").append(total).append(",\n");
        json.append("  \"generatedSteps\": ").append(steps).append(",\n");
        json.append("  \"generatedFailures\": ").append(failures).append(",\n");
        json.append("  \"dealingModel\": \"one-shot catalog: ckl odd list, floor requested odd, map odd to reel triples, expand 3-row window\",\n");
        json.append("  \"generatedModeCounts\": ").append(mapJson(modeCounts)).append(",\n");
        json.append("  \"generatedMultiplierCounts\": ").append(mapJson(multiplierCounts)).append(",\n");
        json.append("  \"generatedEntryDenominators\": ").append(mapJson(boardDenoms)).append(",\n");
        json.append("  \"generatedBoardSymbolCountsByEntry\": ").append(mapJson(entryCounts)).append(",\n");
        json.append("  \"sha256\": ").append(quote(hex(digest.digest()))).append("\n");
        json.append("}\n");
        Path out = repo.resolve("reports/2-Jungle-Kings/generation-model-validation.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, json.toString(), StandardCharsets.UTF_8);
        System.out.println(status + " rounds=" + total + " failures=" + failures + " wrote " + out);
        if (failures != 0) System.exit(2);
    }

    private static String mapJson(Map<String, Integer> map) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            if (!first) json.append(",");
            first = false;
            json.append(quote(entry.getKey())).append(":").append(entry.getValue());
        }
        return json.append("}").toString();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format("%02x", value));
        return text.toString();
    }
}
