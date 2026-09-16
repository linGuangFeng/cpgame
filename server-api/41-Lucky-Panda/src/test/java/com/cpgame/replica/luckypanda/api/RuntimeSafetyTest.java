package com.cpgame.replica.luckypanda.api;

import com.hd.pg.appapi.business.vo.cpgame.luckypanda.GameRuleCore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeSafetyTest {
    @Test
    void controllerSourcesDoNotReadFixturesOrDealAtRequestTime() throws Exception {
        Path root = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            String joined = files.filter(path -> path.toString().endsWith(".java"))
                    .map(path -> {
                        try { return Files.readString(path); }
                        catch (Exception error) { throw new RuntimeException(error); }
                    })
                    .reduce("", (left, right) -> left + "\n" + right);
            assertFalse(joined.contains("fixtures/41-Lucky-Panda"));
            assertFalse(joined.contains("spin-index.jsonl"));
            assertFalse(joined.contains("IndependentLossGenerator"));
            assertTrue(joined.contains("config.getProperty(\"redis.host\""));
            assertFalse(joined.contains("LPOP"));
            assertTrue(joined.contains("LINDEX"));
            assertTrue(joined.contains("ZREVRANGEBYSCORE"));
            assertTrue(joined.contains("redis-db15-complete-round"));
            assertTrue(joined.contains("不准改成内存出牌"));
        }
        assertEquals(41, GameRuleCore.GAME_ID);
        assertEquals("5f8142ce67bb887905edb625ebfb90128fa872cc0de8e9af0f8017debc2dc50c", GameRuleCore.RULES_HASH);
        assertEquals(3, GameRuleCore.SCAT_COLUMN_MAX_BLOCKS);
        assertEquals(5, GameRuleCore.SCAT_TOTAL_MAX_BLOCKS);
    }
}
