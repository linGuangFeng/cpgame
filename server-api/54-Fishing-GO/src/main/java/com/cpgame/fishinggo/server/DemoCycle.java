package com.cpgame.fishinggo.server;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Properties;

/**
 * Demo-only paid-round rotation. Members still come from Redis; this only chooses
 * which pre-generated pool to read. Frontend win popups use RoundReward/CurBetGold
 * with payline_count=20, so Redis odds (rwa/0.02) map as odds/20:
 * small <=5x, BigWin 5x-20x, MegaWin 20x-30x, SuperWin >=30x.
 */
final class DemoCycle {
    static final int PERIOD = 20;
    private static final RedisRoundStore.Selection[] SLOTS = {
            RedisRoundStore.Selection.LOSS,
            RedisRoundStore.Selection.SMALL_WIN,
            RedisRoundStore.Selection.BIG_WIN,
            RedisRoundStore.Selection.MEGA_WIN,
            RedisRoundStore.Selection.SUPER_WIN,
            RedisRoundStore.Selection.SPECIAL_X1,
            RedisRoundStore.Selection.LOSS,
            RedisRoundStore.Selection.SMALL_WIN,
            RedisRoundStore.Selection.BIG_WIN,
            RedisRoundStore.Selection.SPECIAL_X2,
            RedisRoundStore.Selection.LOSS,
            RedisRoundStore.Selection.SMALL_WIN,
            RedisRoundStore.Selection.SUPER_WIN,
            RedisRoundStore.Selection.SPECIAL_X3,
            RedisRoundStore.Selection.LOSS,
            RedisRoundStore.Selection.BIG_WIN,
            RedisRoundStore.Selection.MEGA_WIN,
            RedisRoundStore.Selection.SMALL_WIN,
            RedisRoundStore.Selection.SPECIAL_X1,
            RedisRoundStore.Selection.SUPER_WIN
    };

    static {
        if (SLOTS.length != PERIOD) throw new IllegalStateException("demo cycle length");
        EnumSet<RedisRoundStore.Selection> seen = EnumSet.noneOf(RedisRoundStore.Selection.class);
        for (RedisRoundStore.Selection slot : SLOTS) seen.add(slot);
        if (!seen.containsAll(EnumSet.of(
                RedisRoundStore.Selection.LOSS,
                RedisRoundStore.Selection.SMALL_WIN,
                RedisRoundStore.Selection.BIG_WIN,
                RedisRoundStore.Selection.MEGA_WIN,
                RedisRoundStore.Selection.SUPER_WIN,
                RedisRoundStore.Selection.SPECIAL_X1,
                RedisRoundStore.Selection.SPECIAL_X2,
                RedisRoundStore.Selection.SPECIAL_X3))) {
            throw new IllegalStateException("demo cycle missing a case");
        }
    }

    private DemoCycle() {}

    static RedisRoundStore.Selection at(int paidRoundIndex) {
        return SLOTS[Math.floorMod(paidRoundIndex, PERIOD)];
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            for (int i = 0; i < PERIOD; i++) System.out.printf("%2d %s%n", i + 1, at(i));
            return;
        }
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) { p.load(reader); }
        try (RedisRoundStore store = new RedisRoundStore(p)) {
            for (int i = 0; i < PERIOD; i++) {
                RedisRoundStore.Selection sel = at(i);
                RedisRoundStore.Claim claim = store.claim(sel);
                long sc = claim.round().steps().get(0).board().stream().filter("SC"::equals).count();
                System.out.printf("%2d %-12s odds=%-5d special=%-5s sc=%d steps=%d%n",
                        i + 1, sel, claim.analysis().odds(), claim.round().special(), sc, claim.round().steps().size());
            }
        }
    }

    static RedisRoundStore.Selection parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return switch (raw.toLowerCase(Locale.ROOT).trim()) {
            case "loss" -> RedisRoundStore.Selection.LOSS;
            case "win" -> RedisRoundStore.Selection.WIN;
            case "small", "small_win", "smallwin" -> RedisRoundStore.Selection.SMALL_WIN;
            case "big", "big_win", "bigwin" -> RedisRoundStore.Selection.BIG_WIN;
            case "mega", "mega_win", "megawin" -> RedisRoundStore.Selection.MEGA_WIN;
            case "super", "super_win", "superwin" -> RedisRoundStore.Selection.SUPER_WIN;
            case "special", "free" -> RedisRoundStore.Selection.SPECIAL;
            case "special5", "free5", "x1" -> RedisRoundStore.Selection.SPECIAL_X1;
            case "special6", "free6", "x2" -> RedisRoundStore.Selection.SPECIAL_X2;
            case "special7", "free7", "x3" -> RedisRoundStore.Selection.SPECIAL_X3;
            case "random" -> RedisRoundStore.Selection.ANY;
            default -> null;
        };
    }
}
