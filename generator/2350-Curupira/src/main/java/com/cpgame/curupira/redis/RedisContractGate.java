package com.cpgame.curupira.redis;

import com.cpgame.curupira.model.CompleteRoundFact.Kind;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 平台 Redis Key 合同（结构对齐 2300 分前缀，ID 只属于 2350）。
 * 触发局不进缓存。选关后只有两种结果：Mary 0 = Free Expanding Wild，Mary 1 = Hold &amp; Spins。
 */
public final class RedisContractGate {
    public static final int GAME_ID = 2350;
    public static final int DIGIT_FREE_EW = 0;
    public static final int DIGIT_HOLD = 1;
    public static final int[] ALL_DIGITS = {DIGIT_FREE_EW, DIGIT_HOLD};

    public boolean writable() { return true; }

    public String prefix(int digit, int gameId) {
        requireGame(gameId);
        if (digit < 0 || digit > 9) throw new IllegalArgumentException("redis prefix digit must be 0..9");
        return String.format(Locale.ROOT, "%d%08d", digit, gameId);
    }

    public int digitFor(Kind kind) {
        return switch (kind) {
            case LOSS, WIN, EXPANDING_WILD -> DIGIT_FREE_EW;
            case FREE_EW, BUY_FE -> DIGIT_FREE_EW;
            case HOLD, BUY_HS -> DIGIT_HOLD;
            case TRIGGER -> throw new IllegalArgumentException("trigger boards are live and not cached");
        };
    }

    public String normalIndex(int gameId) {
        return "PerKeyList_" + prefix(DIGIT_FREE_EW, gameId);
    }

    public String specialIndex(int gameId) {
        return "MaryKeyList_" + prefix(DIGIT_FREE_EW, gameId);
    }

    public String indexFor(Kind kind, int gameId) {
        if (kind.ordinary()) return normalIndex(gameId);
        return "MaryKeyList_" + prefix(digitFor(kind), gameId);
    }

    public List<String> indexesToWrite(Kind kind, int gameId) {
        if (kind == Kind.TRIGGER) throw new IllegalArgumentException("trigger boards are live and not cached");
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add(indexFor(kind, gameId));
        if (!kind.ordinary() && digitFor(kind) != DIGIT_FREE_EW) {
            keys.add("PerKeyList_" + prefix(digitFor(kind), gameId));
        }
        return List.copyOf(keys);
    }

    public String listFor(Kind kind, int multiplier, int gameId) {
        if (kind == Kind.TRIGGER) throw new IllegalArgumentException("trigger boards are live and not cached");
        String id = prefix(digitFor(kind), gameId);
        if (kind.ordinary()) {
            return String.format(Locale.ROOT, "BetLog:%s:%06d", id, multiplier);
        }
        return String.format(Locale.ROOT, "MaryLog:%s:%06d", id, multiplier);
    }

    /** SPECIAL 默认落 Mary 0（选 Expanding Wild 后的结果）。 */
    public String resultKey(String pool, int multiplier, int gameId) {
        boolean special = !"ORDINARY_PAID".equals(pool);
        String id = prefix(DIGIT_FREE_EW, gameId);
        return special
                ? String.format(Locale.ROOT, "MaryLog:%s:%06d", id, multiplier)
                : String.format(Locale.ROOT, "BetLog:%s:%06d", id, multiplier);
    }

    public List<String> allIndexKeys(int gameId) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add(normalIndex(gameId));
        keys.add(specialIndex(gameId));
        keys.add("MaryKeyList_" + prefix(DIGIT_HOLD, gameId));
        keys.add("PerKeyList_" + prefix(DIGIT_HOLD, gameId));
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

    private static void requireGame(int gameId) {
        if (gameId <= 0) throw new IllegalArgumentException("redis.game-id must be positive");
    }
}
