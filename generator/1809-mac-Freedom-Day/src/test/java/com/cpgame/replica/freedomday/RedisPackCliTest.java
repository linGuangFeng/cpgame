package com.cpgame.replica.freedomday;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RedisPackCliTest {
    @TempDir Path temp;

    @Test void generatesRandomCompleteRoundsWithOverriddenRedisGameId() throws Exception {
        Path one = temp.resolve("one");
        String[] base = {"--normal-count", "2", "--special-count", "1",
                "--redis-game-id", "8101809", "--max-consecutive-wins", "10",
                "--max-members-per-multiplier", "300"};
        RedisPackCli.main(withOutput(base, one));
        JsonNode manifest = new ObjectMapper().readTree(one.resolve("manifest.json").toFile());
        assertEquals(1809, manifest.path("sourceGameId").asLong());
        assertEquals(8101809, manifest.path("redisGameId").asLong());
        assertEquals(manifest.path("normalMultiplierDistribution").path("0").asInt(),
                manifest.path("ordinaryLossMembers").asInt(-1));
        assertEquals("SecureRandom", manifest.path("randomSource").asText());
        assertTrue(manifest.path("generationPolicy").asText().contains("NATURAL_RANDOM"));
        assertEquals(300, manifest.path("maxMembersPerMultiplier").asInt());
        assertFalse(manifest.has("normalMinMul"));
        assertFalse(manifest.has("specialMaxMul"));
        assertTrue(manifest.path("maxConsecutiveWinsObserved").asInt() <= 10);
        String resp = Files.readString(one.resolve("redis-import.resp"));
        assertTrue(resp.contains("PerKeyList_008101809"));
        assertTrue(resp.contains("MaryKeyList_008101809"));
        assertTrue(resp.contains("LTRIM"));
        for (String line : Files.readAllLines(one.resolve("result-pack.jsonl"))) {
            JsonNode row = new ObjectMapper().readTree(line);
            String payload = row.path("member").asText();
            assertFalse(payload.isBlank());
            assertFalse(payload.startsWith("{") || payload.startsWith("["));
            assertFalse(payload.contains("featureBuy"));
            for (String spin : payload.split("\\|")) {
                if (!spin.matches("#(?:[1-3])?")) assertEquals(0, spin.length() % 34);
            }
            CompleteRoundCodec codec = new CompleteRoundCodec();
            CompleteRoundFact fact = codec.decode(payload, "SPECIAL".equals(row.path("mode").asText()));
            assertFalse(fact.spins().isEmpty());
            assertFalse(fact.spins().get(0).isEmpty());
            codec.verify(payload, 10, fact.featureBuy());
        }
        RedisPackVerifier.main(new String[]{one.resolve("result-pack.jsonl").toString(), "10"});
    }

    @Test void rejectsInvalidCounts() {
        String[] args = {"--output", temp.resolve("bad").toString(), "--normal-count", "0", "--special-count", "0"};
        assertThrows(IllegalArgumentException.class, () -> RedisPackCli.main(args));
    }

    private String[] withOutput(String[] base, Path output) {
        String[] result = new String[base.length + 2];
        result[0] = "--output"; result[1] = output.toString();
        System.arraycopy(base, 0, result, 2, base.length);
        return result;
    }
}
