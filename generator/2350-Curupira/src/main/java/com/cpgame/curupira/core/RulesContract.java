package com.cpgame.curupira.core;

import java.util.List;

public final class RulesContract {
    public static final int GAME_ID = 2350;
    public static final String GAME_NAME = "Curupira";
    public static final String DIRECTORY_NAME = "2350-Curupira";
    public static final String RULES_VERSION = "2350-curupira-rules-d5421efc";
    public static final String RULES_HASH = "d5421efc347392576699048c0538c72d669056017eb723ae2a046fa7bac71404";
    public static final String REDIS_CONTRACT_STATUS = "PLATFORM_CONSUMER_CONTRACT_ENABLED";
    public static final List<String> BEHAVIOR_IDS = List.of(
            "B001_ENTRY_BOOTSTRAP", "B002_LANGUAGE_LOAD", "B003_FIXED_PAYLINES",
            "B004_WILD_SCATTER_RULES", "B005_FREE_EXPANDING_WILD", "B006_HOLD_AND_SPINS",
            "B007_FEATURE_BUY", "B008_INITIAL_CONFIG_REQUEST", "B009_ROOM_INIT",
            "B010_PAID_ROUND", "B011_HISTORY_REQUESTS", "B012_GAME_IDENTITY",
            "B013_MAIN_GAME_EXPANDING_WILD", "B014_HTTP_SIGNING_CODEC", "B015_USER_INFO_REQUEST");
    public static final List<String> UNSUPPORTED_UNKNOWN_BEHAVIORS = List.of();

    private RulesContract() {}
}
