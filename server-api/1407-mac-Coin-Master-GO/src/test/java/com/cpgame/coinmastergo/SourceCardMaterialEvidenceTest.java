package com.cpgame.coinmastergo;

import com.cpgame.coinmastergo.core.GameRules;
import com.cpgame.coinmastergo.core.RoundValidator;
import com.cpgame.coinmastergo.model.SpinStep;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the Silver/Gold state machine to the preserved provider sequences. */
class SourceCardMaterialEvidenceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path captureRoot = Path.of("").toAbsolutePath().normalize()
            .getParent().getParent().resolve("captures/1407-mac-Coin-Master-GO");

    @Test
    void everyCapturedCascadeRefillIsSilverAndEveryMaterialSurvivorMovesExactly() throws Exception {
        Path stepIndex = captureRoot.resolve("step-index.jsonl");
        Path roundIndex = captureRoot.resolve("round-index.jsonl");
        assertTrue(Files.isRegularFile(stepIndex) && Files.isRegularFile(roundIndex),
                "Silver/Gold regression requires the preserved provider indexes");

        Map<Integer, SpinStep> steps = new HashMap<>();
        for (String line : Files.readAllLines(stepIndex)) {
            JsonNode row = mapper.readTree(line);
            steps.put(row.path("sceneIndex").asInt(),
                    mapper.treeToValue(row.path("response").path("data"), SpinStep.class));
        }

        int transitions = 0;
        int eligibleSilverRefills = 0;
        int forbiddenGoldRefills = 0;
        for (String line : Files.readAllLines(roundIndex)) {
            JsonNode round = mapper.readTree(line);
            int start = round.path("startSceneIndex").asInt();
            int end = round.path("endSceneIndex").asInt();
            for (int scene = start; scene < end; scene++) {
                SpinStep previous = steps.get(scene);
                SpinStep successor = steps.get(scene + 1);
                if (previous == null || successor == null || previous.ss != 0) continue;
                RoundValidator.validateCascadeTransition(previous, successor);
                transitions++;

                Set<Integer> winning = new HashSet<>();
                previous.wmkl.forEach(match -> match.forEach(winning::addAll));
                for (int reel = 0; reel < GameRules.REELS; reel++) {
                    int removed = 0;
                    for (int visibleRow = 0; visibleRow < GameRules.VISIBLE_ROWS; visibleRow++) {
                        int raw = reel * 10 + visibleRow;
                        if (winning.contains(raw) && !previous.gfl.contains(raw + 1)) removed++;
                    }
                    for (int position = 0; position < removed; position++) {
                        String symbol = successor.rskl.get(reel * GameRules.TRANSPORT_ROWS + position);
                        if (reel < 1 || reel > 3 || !GameRules.PAYING_SYMBOLS.contains(symbol)) continue;
                        eligibleSilverRefills++;
                        if (successor.gfl.contains(reel * 10 + position)) forbiddenGoldRefills++;
                    }
                }
            }
        }

        assertEquals(679, transitions, "preserved source cascade-pair count changed");
        assertEquals(2_429, eligibleSilverRefills, "preserved eligible Silver-refill count changed");
        assertEquals(0, forbiddenGoldRefills, "source cascade refills never introduce Gold");
    }
}
