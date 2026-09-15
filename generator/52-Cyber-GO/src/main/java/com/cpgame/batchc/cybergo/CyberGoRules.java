package com.cpgame.batchc.cybergo;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 只固化 game-capabilities/protocol-spec 已确认的当前游戏规则。 */
public final class CyberGoRules {
    public static final int GAME_ID = 52;
    public static final String GAME_NAME = "Cyber GO";
    public static final String RULES_HASH = "ae4823db63a5e8bd1d1f3a06da3ed6f76c04d761aefce10f29de6f6502885cfa";
    public static final int REELS = 5;
    public static final int ROWS = 3;
    public static final int VISIBLE_CELLS = 15;
    public static final int BASIC_BET_FACTOR = 30;
    public static final BigDecimal MINIMUM_BET_SIZE = new BigDecimal("0.02");
    public static final int MINIMUM_BET_LEVEL = 1;
    public static final BigDecimal MINIMUM_BET = new BigDecimal("0.60");
    public static final String WILD = "WILD";
    public static final String SCATTER = "SC";
    public static final List<String> PAYING_SYMBOLS = List.of("S1", "S2", "S3", "S4", "A", "K", "Q", "J");
    public static final Map<String, Map<Integer, Integer>> PAYTABLE = Map.of(
            "A", Map.of(3, 8, 4, 10, 5, 30),
            "J", Map.of(3, 5, 4, 8, 5, 15),
            "K", Map.of(3, 8, 4, 10, 5, 30),
            "Q", Map.of(3, 5, 4, 8, 5, 15),
            "S1", Map.of(3, 30, 4, 60, 5, 150),
            "S2", Map.of(3, 20, 4, 45, 5, 90),
            "S3", Map.of(3, 15, 4, 30, 5, 60),
            "S4", Map.of(3, 10, 4, 15, 5, 45));
    public static final Map<Integer, Integer> FREE_SPIN_AWARDS = Map.of(3, 12, 4, 15, 5, 20);

    private CyberGoRules() { }
}
