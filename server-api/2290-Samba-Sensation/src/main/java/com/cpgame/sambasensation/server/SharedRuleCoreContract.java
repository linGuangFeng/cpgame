package com.cpgame.sambasensation.server;

import com.cpgame.sambasensation.core.GameRuleCore;

import java.util.List;

/** 启动时绑定已验收 handoff；Controller 不接受旧 rulesHash，也不另建判奖实现。 */
public final class SharedRuleCoreContract {
    public static final String RULES_HASH = "46ef48cf0977a2785f257825d1e499b8049d293900e2a10d4a0dec7ad1ba2205";
    public static final List<String> BEHAVIOR_IDS = List.of(
            "BHV-SESSION-INIT", "BHV-BET-AXIS", "BHV-PAID-ROUND", "BHV-ORDINARY-LOSS",
            "BHV-ORDINARY-WIN", "BHV-PAYLINE-WILD", "BHV-SCATTER-COLLECTION",
            "BHV-FREE-SPINS", "BHV-FEATURE-BUY", "BHV-COIN-COLLECTION-REWARD", "BHV-HISTORY");

    private SharedRuleCoreContract() { }

    public static void verify() {
        if (GameRuleCore.GAME_ID != 2290) throw new IllegalStateException("sourceGameId mismatch");
        if (!RULES_HASH.equals(GameRuleCore.RULES_HASH)) {
            throw new IllegalStateException("rulesHash mismatch with protocol-handoff.json");
        }
        if (GameRuleCore.FREE_STEPS != 5 || GameRuleCore.PAYLINES.length != 25) {
            throw new IllegalStateException("shared GameRuleCore contract mismatch");
        }
    }
}
