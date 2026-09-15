package com.cpgame.luckycatii;

import com.cpgame.luckycatii.model.ResultAnalysis;
import com.cpgame.luckycatii.model.RoundFacts;
import com.cpgame.luckycatii.model.RoundResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class ResultUtilOracleTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void independentOracleMatchesEveryCapturedWireRound() throws Exception {
        Path file = Path.of("..", "..", "fixtures", "50-Lucky-Cat-II", "spin-responses.jsonl");
        assertTrue(Files.isRegularFile(file), file.toAbsolutePath().toString());
        int checked = 0;
        int lucky = 0;
        int wheel = 0;
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) continue;
            JsonNode data = JSON.readTree(line).path("response").path("data");
            List<String> rskl = texts(data.get("rskl"));
            List<String> rdskl = texts(data.get("rdskl"));
            int gm = data.get("gm").asInt();
            int rpx = data.get("rpx").asInt();
            int rdri = data.get("rdri").asInt();
            List<String> paid = new ArrayList<>(rskl);
            if (gm == 1) {
                for (int row = 0; row < 3; row++) paid.set(rdri * 3 + row, rdskl.get(row));
            }
            BigDecimal bs = data.hasNonNull("bs") ? new BigDecimal(data.get("bs").asText()) : new BigDecimal("0.1");
            int bl = data.hasNonNull("bl") ? data.get("bl").asInt() : 1;
            RoundFacts facts = new RoundFacts("oracle-" + checked, 1_700_000_001L, bs, bl, paid, rskl, rpx, gm == 1);
            RoundResult restored = new RoundFactory().restore(facts);
            ResultAnalysis inferred = new RoundVerifier().verify(restored);
            Map<Integer, String> wmkl = new TreeMap<>();
            data.get("wmkl").fields().forEachRemaining(e -> wmkl.put(Integer.parseInt(e.getKey()), e.getValue().asText()));
            assertEquals(wmkl, inferred.winningLines(), "wmkl " + checked);
            assertEquals(0, inferred.award().compareTo(new BigDecimal(data.get("wa").asText())), "wa " + checked);
            assertEquals(gm, inferred.gameMode());
            assertEquals(rpx, inferred.rpx());
            if (gm == 1) {
                assertEquals(rdri, inferred.respinReelIndex());
                lucky++;
            }
            if (rpx > 1) wheel++;
            checked++;
        }
        assertEquals(1565, checked);
        assertEquals(50, lucky);
        assertEquals(48, wheel);
    }

    private static List<String> texts(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) node.forEach(item -> values.add(item.asText()));
        return values;
    }
}
