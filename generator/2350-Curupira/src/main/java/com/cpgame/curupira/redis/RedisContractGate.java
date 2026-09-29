package com.cpgame.curupira.redis;

import com.cpgame.curupira.model.CompleteRoundFact.Kind;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 2350 稳定映射：普通付费起点 Per 0；Free Expanding Wild Mary 0；Hold & Spins Mary 1。 */
public final class RedisContractGate {
    public static final int GAME_ID = 2350;
    public static final int NORMAL_TYPE = 0;
    public static final int MARY_FREE_EXPANDING_WILD_TYPE = 0;
    public static final int MARY_HOLD_AND_SPINS_TYPE = 1;

    public boolean writable() { return true; }

    public String prefix(int digit, int gameId) {
        requireGame(gameId);
        if (digit < 0 || digit > 9) throw new IllegalArgumentException("redis prefix digit must be 0..9");
        return String.format(Locale.ROOT, "%d%08d", digit, gameId);
    }

    public int digitFor(Kind kind) {
        return switch (kind) {
            case LOSS, WIN, EXPANDING_WILD, TRIGGER, FREE_EW, BUY_FE -> MARY_FREE_EXPANDING_WILD_TYPE;
            case HOLD, BUY_HS -> MARY_HOLD_AND_SPINS_TYPE;
        };
    }

    public String normalIndex(int gameId) {
        return "PerKeyList_" + prefix(NORMAL_TYPE, gameId);
    }

    public String indexFor(Kind kind, int gameId) {
        if (isPaidStart(kind)) return normalIndex(gameId);
        return "MaryKeyList_" + prefix(digitFor(kind), gameId);
    }

    public List<String> indexesToWrite(Kind kind, int gameId) {
        return List.of(indexFor(kind, gameId));
    }

    public String listFor(Kind kind, int multiplier, int gameId) {
        String id = prefix(digitFor(kind), gameId);
        return isPaidStart(kind)
                ? String.format(Locale.ROOT, "BetLog:%s:%06d", id, multiplier)
                : String.format(Locale.ROOT, "MaryLog:%s:%06d", id, multiplier);
    }

    public String resultKey(String pool, int multiplier, int gameId) {
        String id = prefix(NORMAL_TYPE, gameId);
        return "ORDINARY_PAID".equals(pool)
                ? String.format(Locale.ROOT, "BetLog:%s:%06d", id, multiplier)
                : String.format(Locale.ROOT, "MaryLog:%s:%06d", id, multiplier);
    }

    public List<String> allIndexKeys(int gameId) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add(normalIndex(gameId));
        keys.add("MaryKeyList_" + prefix(MARY_FREE_EXPANDING_WILD_TYPE, gameId));
        keys.add("MaryKeyList_" + prefix(MARY_HOLD_AND_SPINS_TYPE, gameId));
        return List.copyOf(keys);
    }

    public List<String> scanPatterns(int gameId) {
        requireGame(gameId);
        String pad8 = String.format(Locale.ROOT, "%08d", gameId);
        String pad9 = String.format(Locale.ROOT, "%09d", gameId);
        List<String> patterns = new ArrayList<>();
        for (String pad : List.of(pad8, pad9)) {
            patterns.add("PerKeyList_*" + pad + "*");
            patterns.add("MaryKeyList_*" + pad + "*");
            patterns.add("BetLog:*" + pad + "*");
            patterns.add("MaryLog:*" + pad + "*");
        }
        return List.copyOf(patterns);
    }

    public Set<String> requiredIndexNames(int gameId) {
        return new LinkedHashSet<>(allIndexKeys(gameId));
    }

    private static boolean isPaidStart(Kind kind) {
        return kind == Kind.LOSS || kind == Kind.WIN || kind == Kind.EXPANDING_WILD || kind == Kind.TRIGGER;
    }

    private static void requireGame(int gameId) {
        if (gameId <= 0) throw new IllegalArgumentException("redis.game-id must be positive");
    }
}
