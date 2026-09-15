package com.cpgame.admin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CpgameRedisCacheServiceTest {
    @Test
    void prefixesKeepBetTypeDigitWithoutOrdinarySpecialLabels() {
        List<Long> ids = List.of(2290L);
        assertEquals("PerKeyList_1", CpgameRedisCacheService.groupName("PerKeyList_100002290", ids));
        assertEquals("PerKeyList_0", CpgameRedisCacheService.groupName("PerKeyList_000002290", ids));
        assertEquals("MaryKeyList_0", CpgameRedisCacheService.groupName("MaryKeyList_000002290", ids));
        assertEquals("PerKeyList_1", CpgameRedisCacheService.groupName("BetLog:100002290:000160", ids));
        assertEquals("MaryKeyList_0", CpgameRedisCacheService.groupName("MaryLog:000002290:000020", ids));
        assertEquals("PerKeyList_0", CpgameRedisCacheService.groupName("PerKeyList_000000043", List.of(43L)));
        assertEquals("PerKeyList_1", CpgameRedisCacheService.groupName("PerKeyList_100000043", List.of(43L)));
        assertEquals("MaryKeyList_0", CpgameRedisCacheService.groupName("MaryKeyList_000000043", List.of(43L)));
        assertEquals("MaryKeyList_1", CpgameRedisCacheService.groupName("MaryKeyList_100000043", List.of(43L)));
        assertEquals("PerKeyList_1", CpgameRedisCacheService.groupName("BetLog:100000043:000050", List.of(43L)));
        assertEquals("MaryKeyList_1", CpgameRedisCacheService.groupName("MaryLog:100000043:000250", List.of(43L)));
        assertNull(CpgameRedisCacheService.groupName(
            "cpgame:runtime:16:jungle-fruit:empirical-v1:pool:MARY:18", List.of(16L)));
        assertNull(CpgameRedisCacheService.groupName(
            "cpgame:runtime:16:jungle-fruit:pool:FREE:147", List.of(16L)));
        assertNull(CpgameRedisCacheService.groupName("PerKeyList_100002290", List.of(16L)));
        assertEquals("PerKeyList_1", CpgameRedisCacheService.groupName("PerKeyList_100002290", List.of(2290L)));
    }

    @Test
    void multiplierDisplayStripsPadding() {
        assertEquals("20", CpgameRedisCacheService.displayMultiplier("000020", "MaryLog:000002290:000020"));
        assertEquals("18", CpgameRedisCacheService.displayMultiplier("member", "cpgame:runtime:16:jungle-fruit:empirical-v1:pool:MARY:18"));
    }

    @Test
    void knownIndexKeysCoverBetTypeDigits() {
        List<String> keys = CpgameRedisCacheService.knownIndexKeys(List.of(8L, 2290L));
        assertTrue(keys.contains("PerKeyList_000000008"));
        assertTrue(keys.contains("PerKeyList_100000008"));
        assertTrue(keys.contains("MaryKeyList_000002290"));
        assertTrue(keys.contains("MaryKeyList_100002290"));
    }

    @Test
    void countKeysMustBelongToTheOpenGame() {
        assertTrue(CpgameRedisCacheService.belongsToGame("BetLog:000000061:000160", List.of(61L)));
        assertTrue(CpgameRedisCacheService.belongsToGame("MaryLog:000000061:000020", List.of(61L)));
        assertFalse(CpgameRedisCacheService.belongsToGame("BetLog:000002290:000160", List.of(61L)));
        assertEquals(500, CpgameRedisCacheService.PIPELINE);
    }

    @Test
    void multiplierRangeFollowsIndexMax() {
        assertEquals(0, CpgameRedisCacheService.parsedMultiplier("0"));
        assertEquals(19999, CpgameRedisCacheService.parsedMultiplier("019999"));
        assertEquals(19999, CpgameRedisCacheService.multiplierCeiling("19999"));
        assertEquals(CpgameRedisCacheService.MAX_MULTIPLIER,
            CpgameRedisCacheService.multiplierCeiling("500000"));
    }

    @Test
    void listKeyMapsBackToIndexAndMemberVariants() {
        assertEquals("PerKeyList_000001407",
            CpgameRedisCacheService.indexKeyForList("BetLog:000001407:000001"));
        assertEquals("MaryKeyList_000001407",
            CpgameRedisCacheService.indexKeyForList("MaryLog:000001407:000100"));
        assertEquals("PerKeyList_100002290",
            CpgameRedisCacheService.indexKeyForList("BetLog:100002290:000160"));
        List<String> members = CpgameRedisCacheService.indexMembersForMultiplier("BetLog:000001407:000001", "1");
        assertTrue(members.contains("1"));
        assertTrue(members.contains("000001"));
    }

    @Test
    void emptyKeyIsIndexedMultiplierWithoutFillingGaps() {
        assertEquals(1, CpgameRedisCacheService.parsedMultiplier("000001"));
        assertEquals("1", CpgameRedisCacheService.displayMultiplier("000001", null));
        assertEquals("20", CpgameRedisCacheService.displayMultiplier("000020", "BetLog:000001407:000020"));
        List<String> indexed = List.of("000001", "000020", "001500");
        List<Long> values = indexed.stream().map(CpgameRedisCacheService::parsedMultiplier).toList();
        assertEquals(List.of(1L, 20L, 1500L), values);
        assertFalse(values.contains(0L));
        assertFalse(values.contains(2L));
    }

    @Test
    void memorySampleUsesTwoPercentOfEachMultiplierCount() {
        assertEquals(0.02, CpgameRedisCacheService.MEMORY_SAMPLE_RATE);
        assertEquals(0, CpgameRedisCacheService.memorySampleCount(0));
        assertEquals(1, CpgameRedisCacheService.memorySampleCount(1));
        assertEquals(1, CpgameRedisCacheService.memorySampleCount(50));
        assertEquals(2, CpgameRedisCacheService.memorySampleCount(51));
        assertEquals(8, CpgameRedisCacheService.MEMORY_SAMPLE_CAP);
        assertEquals(8, CpgameRedisCacheService.memorySampleCount(1000));
        List<String> keys = new ArrayList<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            String key = "BetLog:000000061:" + String.format("%06d", i);
            keys.add(key);
            counts.put(key, i % 2 == 0 ? 0L : 12L);
        }
        List<String> sample = CpgameRedisCacheService.sampleKeysForMemory(keys, counts);
        assertEquals(10, sample.size());
        assertEquals(keys.get(1), sample.get(0));
        assertFalse(sample.contains(keys.get(0)));
        Map<String, Long> memory = Map.of(
            sample.get(0), 1200L,
            sample.get(1), 2400L);
        Map<String, Long> sampleCounts = Map.of(sample.get(0), 12L, sample.get(1), 12L);
        assertEquals(27000L, CpgameRedisCacheService.estimateMemoryBytes(memory, sampleCounts, 180L));
        assertTrue(CpgameRedisCacheService.memoryCommandUnsupported(
            new IOException("Redis error: ERR unknown command 'MEMORY'")));
        assertFalse(CpgameRedisCacheService.memoryCommandUnsupported(new IOException("Read timed out")));
    }

    @Test
    void scanPatternsStayScopedToOneGame() {
        List<String> patterns = CpgameRedisCacheService.scanPatterns(List.of(8L, 2290L));
        assertTrue(patterns.contains("BetLog:000000008:*"));
        assertTrue(patterns.contains("MaryLog:000002290:*"));
        assertTrue(patterns.contains("BetLog:100000008:*"));
        assertEquals(List.of("PerKeyList_*", "MaryKeyList_*"), CpgameRedisCacheService.indexScanPatterns());
        assertTrue(patterns.stream().noneMatch(pattern ->
            pattern.equals("BetLog:*") || pattern.equals("MaryLog:*")
                || pattern.equals("PerKeyList_*") || pattern.equals("MaryKeyList_*")
                || pattern.startsWith("*")));
    }
}
