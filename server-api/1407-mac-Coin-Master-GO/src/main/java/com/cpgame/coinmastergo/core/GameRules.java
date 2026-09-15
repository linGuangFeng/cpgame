package com.cpgame.coinmastergo.core;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GameRules {
    public static final String RULES_VERSION = "1407-mac-evidence-v18.4";
    public static final String RULES_HASH = "08727f7e5a890dcd1b3cf5ae9559474ef9ea98c6e9a09ac34b7d53598ba1662f";
    public static final int PLATFORM_GAME_ID = 1407;
    public static final int GAME_PROTOCOL_ID = 55;
    public static final int REELS = 5;
    public static final int VISIBLE_ROWS = 4;
    public static final int TRANSPORT_ROWS = 5;
    public static final int TRANSPORT_CELLS = 25;
    public static final int CAPTURED_MAX_FSN = 36;
    public static final int FIXED_WAYS = 1024;
    public static final BigDecimal BASE_BET_FACTOR = new BigDecimal("20");
    public static final List<Integer> BET_LEVELS = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    public static final List<BigDecimal> BET_SIZES = List.of(
            new BigDecimal("0.02"), new BigDecimal("0.1"), new BigDecimal("0.5"));
    public static final List<String> PAYING_SYMBOLS = List.of("H1", "H2", "H3", "H4", "H5", "H6", "H7", "H8");
    public static final Set<String> SYMBOLS = Set.of("H1", "H2", "H3", "H4", "H5", "H6", "H7", "H8", "WILD", "SC");
    /**
     * Exact symbol counts from the 501 preserved paid-Round starts (12,525 transport cells).
     * This is an evidence-derived local sampling profile, not a claim about provider weights.
     * WILD has weight zero because all captured initial steps contain zero WILD; WILD is created
     * only when a winning golden symbol survives into the next cascade.
     */
    public static final Map<String, Integer> EVIDENCE_START_SYMBOL_WEIGHTS = Map.ofEntries(
            Map.entry("H1", 1595), Map.entry("H2", 1569), Map.entry("H3", 1542),
            Map.entry("H4", 1489), Map.entry("H5", 1508), Map.entry("H6", 1547),
            Map.entry("H7", 1500), Map.entry("H8", 1459), Map.entry("WILD", 0),
            Map.entry("SC", 316));
    /**
     * Among 7,310 eligible cards on reels 2-4 in the same 501 starts, 5,737 used the
     * ordinary/silver face and 1,573 were listed by gfl as gold. The ratio is inferred
     * per eligible card; it remains a sample estimate rather than a provider RTP claim.
     */
    public static final int EVIDENCE_SILVER_CARD_WEIGHT = 5_737;
    public static final int EVIDENCE_GOLD_CARD_WEIGHT = 1_573;
    public static final Map<String, Map<Integer, BigDecimal>> PAYTABLE = Map.of(
            "H1", pay(15, 60, 100), "H2", pay(10, 40, 80),
            "H3", pay(8, 20, 60), "H4", pay(6, 15, 40),
            "H5", pay(4, 10, 20), "H6", pay(4, 10, 20),
            "H7", pay(2, 5, 10), "H8", pay(2, 5, 10));
    public static final List<Integer> BASE_RPX = List.of(1, 2, 3, 5);
    public static final List<Integer> FREE_RPX = List.of(2, 4, 6, 10);

    private GameRules() { }

    /**
     * Free-spin award from visible SC on a Delivery's terminal board (ss=1).
     * Captured retriggers such as scenes 182-187 and 2280-2283 complete 3+ SC
     * during cascade; the opening board of that Delivery is not the award source.
     */
    public static int freeAward(int visibleScatterCount) {
        return visibleScatterCount < 3 ? 0 : 12 + 2 * (visibleScatterCount - 3);
    }

    private static Map<Integer, BigDecimal> pay(int reel3, int reel4, int reel5) {
        return Map.of(3, BigDecimal.valueOf(reel3), 4, BigDecimal.valueOf(reel4), 5, BigDecimal.valueOf(reel5));
    }

    public static BigDecimal betAmount(int betLevel, BigDecimal betSize) {
        return BASE_BET_FACTOR.multiply(BigDecimal.valueOf(betLevel)).multiply(betSize);
    }
}
