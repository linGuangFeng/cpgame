package com.cpgame.replica.luckypanda.api;

import com.hd.pg.appapi.business.vo.cpgame.luckypanda.GameRuleCore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolHandoffGuardTest {
    @Test
    void rulesHashMatchesCanonicalFileAndHandoff() throws Exception {
        Path canonical = Path.of("D:/work/hd/cpgame/protocol/41-Lucky-Panda/rules-core-canonical.json");
        byte[] bytes = Files.readAllBytes(canonical);
        String digest = java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(GameRuleCore.RULES_HASH, digest);
        String handoff = Files.readString(
                Path.of("D:/work/hd/cpgame/protocol/41-Lucky-Panda/protocol-handoff.json"),
                StandardCharsets.UTF_8);
        assertTrue(handoff.contains(GameRuleCore.RULES_HASH));
        assertTrue(handoff.contains("B-SCATTER-FREE"));
        assertTrue(handoff.contains("U-BUY-FEATURE"));
        assertTrue(handoff.contains("NOT_A_PLAY_MODE") || handoff.contains("not as mali-via-buy"));
    }
}
