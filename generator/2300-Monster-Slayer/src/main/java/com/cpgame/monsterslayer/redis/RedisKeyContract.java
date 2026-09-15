package com.cpgame.monsterslayer.redis;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Redis identity for game 2300.
 * Ordinary members live in BetLog:000002300 and are indexed by PerKeyList_000002300.
 * Purchase members live in MaryLog:{prefix} and are indexed by MaryKeyList_{prefix}.
 * Purchase prefixes: type=3 → 000002300, type=4 → 100002300, type=5 → 200002300.
 * Each index refers to a real result list; purchases use MaryKeyList only.
 * Ordinary and purchase members never share a LIST.
 */
public final class RedisKeyContract {
    public static final long GAME_ID = 2300L;
    public static final String PREFIX_ORDINARY_AND_BUY1 = "000002300";
    public static final String PREFIX_BUY2 = "100002300";
    public static final String PREFIX_BUY3 = "200002300";

    private RedisKeyContract() { }

    public static String normalIndex(long gameId) {
        requireGame(gameId);
        return "PerKeyList_" + prefix(gameId, 0);
    }

    public static String specialIndex(long gameId) {
        requireGame(gameId);
        return "MaryKeyList_" + prefix(gameId, 0);
    }

    public static String normalList(long gameId, int centiMultiplier) {
        requireGame(gameId);
        return String.format(Locale.ROOT, "BetLog:%s:%06d", prefix(gameId, 0), centiMultiplier);
    }

    public static String specialList(long gameId, int centiMultiplier) {
        requireGame(gameId);
        return String.format(Locale.ROOT, "MaryLog:%s:%06d", prefix(gameId, 0), centiMultiplier);
    }

    private static String prefix(long gameId, int mode) {
        requireGame(gameId);
        return String.format(Locale.ROOT, "%d%08d", mode, gameId);
    }

    public static String buyPrefix(int buyType) { return buyPrefix(GAME_ID, buyType); }
    public static String buyPrefix(long gameId, int buyType) {
        if (buyType < 3 || buyType > 5) throw new IllegalArgumentException("buy type must be 3/4/5");
        return prefix(gameId, buyType - 3);
    }

    public static String buyIndex(int buyType) { return buyIndex(GAME_ID, buyType); }
    public static String buyIndex(long gameId, int buyType) { return "MaryKeyList_" + buyPrefix(gameId, buyType); }
    /** Legacy redundant index name, retained only for cleanup and source compatibility. */
    public static String buyPerKeyIndex(int buyType) { return buyPerKeyIndex(GAME_ID, buyType); }
    public static String buyPerKeyIndex(long gameId, int buyType) { return "PerKeyList_" + buyPrefix(gameId, buyType); }
    public static String buyMaryIndex(int buyType) { return buyIndex(buyType); }
    public static String buyMaryIndex(long gameId, int buyType) { return buyIndex(gameId, buyType); }
    public static String buyList(int buyType, int centiMultiplier) { return buyList(GAME_ID, buyType, centiMultiplier); }
    public static String buyList(long gameId, int buyType, int centiMultiplier) {
        return String.format(Locale.ROOT, "MaryLog:%s:%06d", buyPrefix(gameId, buyType), centiMultiplier);
    }
    public static String buyBetLog(int buyType, int centiMultiplier) { return buyBetLog(GAME_ID, buyType, centiMultiplier); }
    public static String buyBetLog(long gameId, int buyType, int centiMultiplier) {
        return String.format(Locale.ROOT, "BetLog:%s:%06d", buyPrefix(gameId, buyType), centiMultiplier);
    }

    public static String legacyBuyIndex(int buyType) {
        return switch (buyType) {
            case 3 -> "PerKeyListt_0";
            case 4 -> "PerKeyListt_1";
            case 5 -> "PerKeyListt_2";
            default -> throw new IllegalArgumentException("buy type must be 3/4/5");
        };
    }

    /** Indexes written for a purchase mode. Type=3 must not overwrite PerKeyList_000002300. */
    public static List<String> buyIndexesToWrite(int buyType) { return buyIndexesToWrite(GAME_ID, buyType); }
    public static List<String> buyIndexesToWrite(long gameId, int buyType) {
        return List.of(buyIndex(gameId, buyType));
    }

    public static List<String> buyIndexesToDelete(int buyType) { return buyIndexesToDelete(GAME_ID, buyType); }
    public static List<String> buyIndexesToDelete(long gameId, int buyType) {
        LinkedHashSet<String> keys = new LinkedHashSet<>(buyIndexesToWrite(gameId, buyType));
        // Retire the old PerKeyList aliases; there are no corresponding BetLog result lists.
        if (buyType != 3) keys.add(buyPerKeyIndex(gameId, buyType));
        // Unscoped legacy indexes only belong to the original 2300 namespace.
        if (gameId == GAME_ID) keys.add(legacyBuyIndex(buyType));
        return List.copyOf(keys);
    }

    public static boolean belongsToGame2300(String key) {
        if (key == null) return false;
        return key.contains(PREFIX_ORDINARY_AND_BUY1)
                || key.contains(PREFIX_BUY2)
                || key.contains(PREFIX_BUY3)
                || key.equals("PerKeyListt_0")
                || key.equals("PerKeyListt_1")
                || key.equals("PerKeyListt_2");
    }

    public static Set<String> requiredIndexNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add(normalIndex(GAME_ID));
        names.add(buyIndex(3));
        names.add(buyIndex(4));
        names.add(buyIndex(5));
        return names;
    }

    private static void requireGame(long gameId) {
        if (gameId < 1 || gameId > 99_999_999) throw new IllegalArgumentException("redis.game-id must be 1..99999999");
    }
}
