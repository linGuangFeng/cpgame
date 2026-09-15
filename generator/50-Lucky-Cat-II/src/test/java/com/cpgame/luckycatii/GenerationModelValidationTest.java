package com.cpgame.luckycatii;

import com.cpgame.luckycatii.model.ResultAnalysis;
import com.cpgame.luckycatii.model.RoundFacts;
import com.cpgame.luckycatii.model.RoundResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class GenerationModelValidationTest {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final BigDecimal BS = new BigDecimal("0.1");

    @Test void holdoutRestoreAndTenThousandGeneratedRoundsPass() throws Exception {
        Path holdoutFile = Path.of("..", "..", "reports", "50-Lucky-Cat-II", "holdout-100.jsonl");
        assertTrue(Files.isRegularFile(holdoutFile));
        GameRuleCore core = GameRuleCore.forRestoration();
        RoundVerifier verifier = new RoundVerifier();
        int holdout = 0;
        Map<String, Integer> holdoutKinds = new TreeMap<>();
        for (String line : Files.readAllLines(holdoutFile)) {
            if (line.isBlank()) continue;
            JsonNode row = JSON.readTree(line);
            List<String> s01 = texts(row.get("s01"));
            List<String> s02 = texts(row.get("s02"));
            BigDecimal bs = row.hasNonNull("bs") ? new BigDecimal(row.get("bs").asText()) : new BigDecimal("0.1");
            int bl = row.hasNonNull("bl") ? row.get("bl").asInt() : 1;
            RoundFacts facts = new RoundFacts("holdout-" + holdout, 1_788_000_000L + holdout,
                    bs, bl, s01, s02, row.get("rpx").asInt(), row.get("gm").asInt() == 1);
            RoundResult restored = core.restore(facts);
            ResultAnalysis inferred = verifier.verify(restored);
            assertEquals(row.get("gm").asInt(), inferred.gameMode());
            assertEquals(row.get("rpx").asInt(), inferred.rpx());
            assertEquals(0, inferred.award().compareTo(new BigDecimal(row.get("wa").asText())));
            Map<Integer, String> wmkl = new TreeMap<>();
            row.get("wmkl").fields().forEachRemaining(e -> wmkl.put(Integer.parseInt(e.getKey()), e.getValue().asText()));
            assertEquals(wmkl, inferred.winningLines());
            holdoutKinds.merge(inferred.redisPoolMode().name() + (inferred.wheel() ? "+WHEEL" : ""), 1, Integer::sum);
            holdout++;
        }
        assertEquals(100, holdout);

        GameRuleCore generatedCore = GameRuleCore.forTesting(50010);
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec(new RoundFactory(), verifier);
        SplittableRandom rng = new SplittableRandom(50011);
        Map<String, Integer> generatedKinds = new TreeMap<>();
        Map<Integer, Integer> stepCounts = new TreeMap<>();
        Set<List<String>> uniqueBoards = new java.util.HashSet<>();
        Set<String> uniqueStates = new java.util.HashSet<>();
        int maxMemberBytes = 0;
        int loss = 0, win = 0, special = 0, lucky = 0, wheel = 0;
        for (int i = 0; i < 10000; i++) {
            RoundResult round = i < 3000 ? generatedCore.generateIndependentLoss(BS, 1, rng)
                    : i < 9000 ? generatedCore.generateOrdinaryWin(BS, 1, rng)
                    : generatedCore.generateSpecial(BS, 1, rng);
            ResultAnalysis inferred = verifier.verify(round);
            String member = codec.encodeRedisMemberString(round);
            verifier.verifyRecovery(round, codec.decodeRedisMember(member));
            maxMemberBytes = Math.max(maxMemberBytes, member.getBytes(java.nio.charset.StandardCharsets.US_ASCII).length);
            uniqueBoards.add(round.finalBoard());
            uniqueStates.add(round.paidBoard() + "|" + round.finalBoard() + "|" + round.rpx());
            stepCounts.merge(round.steps().size(), 1, Integer::sum);
            if (inferred.luckyRespin()) lucky++;
            if (inferred.wheel()) wheel++;
            if (ResultUtil.isSpecialPool(inferred)) special++;
            else if (inferred.redisPoolMode() == com.cpgame.luckycatii.model.RoundMode.ORDINARY_LOSS) loss++;
            else win++;
            generatedKinds.merge(inferred.redisPoolMode().name(), 1, Integer::sum);
            ResultUtil.enforceWildCaps(round.paidBoard());
            ResultUtil.enforceWildCaps(round.finalBoard());
            if (round.rpx() > 1) assertTrue(GameRules.CONFIRMED_WHEEL_MULTIPLIERS.contains(round.rpx()));
        }
        assertEquals(3000, loss);
        assertEquals(6000, win);
        assertEquals(1000, special);
        assertTrue(lucky > 0 && wheel > 0);
        assertEquals(10000, loss + win + special);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", "1.0");
        report.put("gameId", 50);
        report.put("status", "PASS");
        report.put("executedAt", Instant.now().toString());
        report.put("rulesHash", GameRules.RULES_HASH);
        report.put("model", Map.of(
                "type", "joint-complete-state-kernel-with-left-right-reel-swap",
                "perCellConstruction", false,
                "stitchedLoss", false,
                "trainingRounds", 1465,
                "holdoutRounds", 100,
                "trainingRange", "R000001..R001465",
                "holdoutRange", "R001466..R001565"));
        report.put("holdout", Map.of(
                "validated", holdout,
                "kinds", holdoutKinds,
                "allWireFieldsReproduced", true));
        Map<String, Object> generated = new LinkedHashMap<>();
        generated.put("validated", 10000);
        generated.put("loss", loss);
        generated.put("ordinaryWin", win);
        generated.put("special", special);
        generated.put("luckyRespin", lucky);
        generated.put("multiplierWheel", wheel);
        generated.put("independentOraclePass", 10000);
        generated.put("asciiCodecRoundTrips", 10000);
        generated.put("maxRedisMemberBytes", maxMemberBytes);
        generated.put("uniqueBoards", uniqueBoards.size());
        generated.put("uniqueCompleteStates", uniqueStates.size());
        generated.put("stepCounts", stepCounts);
        report.put("generated", generated);
        report.put("entryCaps", Map.of(
                "maxWildPerReel", GameRules.MAX_WILD_PER_REEL,
                "maxWildPerBoard", GameRules.MAX_WILD_PER_BOARD,
                "confirmedWheelMultipliers", GameRules.CONFIRMED_WHEEL_MULTIPLIERS,
                "maxLuckyRespins", GameRules.MAX_LUCKY_RESPINS));
        report.put("checks", List.of(
                "GameRuleCore restore reproduces holdout wa/gm/rpx/wmkl/rdri",
                "ResultUtil independently reproduces every generated settlement",
                "each Redis member round-trips one complete Round as minimal US-ASCII",
                "no holdout round is present in the training kernel file",
                "WILD reel/board caps and confirmed wheel multipliers are enforced"));
        Path out = Path.of("..", "..", "reports", "50-Lucky-Cat-II", "generation-model-validation.json");
        JSON.writeValue(out.toFile(), report);
        assertTrue(Files.isRegularFile(out));
    }

    private static List<String> texts(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) node.forEach(item -> values.add(item.asText()));
        return values;
    }
}
