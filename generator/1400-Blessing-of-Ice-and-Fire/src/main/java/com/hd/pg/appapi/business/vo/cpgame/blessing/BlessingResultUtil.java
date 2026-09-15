package com.hd.pg.appapi.business.vo.cpgame.blessing;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Independent evaluator for Blessing of Ice and Fire (gid 1400).
 * Column-major 3x3. Win reads the middle row only (indices 1, 4, 7).
 * Same board always yields the same odd. Does not read Redis or fixtures.
 */
public final class BlessingResultUtil {
    public static final int GID = 1400;
    public static final int COLS = 3;
    public static final int ROWS = 3;
    public static final int CELLS = 9;
    public static final int EMPTY = 0;
    public static final int SYM_HIGH = 1;
    public static final int SYM_MID = 2;
    public static final int SYM_LOW = 3;

    /** prop_odds from origin initRoom. */
    public static final int ODD_HIGH = 100;
    public static final int ODD_MID = 50;
    public static final int ODD_LOW = 25;
    public static final int ODD_ANY = 5;

    private BlessingResultUtil() { }

    public static Map<String, Integer> payTable() {
        Map<String, Integer> table = new LinkedHashMap<>();
        table.put("1", ODD_HIGH);
        table.put("2", ODD_MID);
        table.put("3", ODD_LOW);
        table.put("4", ODD_ANY);
        return table;
    }

    public static int[] middles(int[] p) {
        requireBoard(p);
        return new int[] {p[1], p[4], p[7]};
    }

    public static int evaluateOdd(int[] p) {
        int[] w = middles(p);
        if (w[0] == EMPTY || w[1] == EMPTY || w[2] == EMPTY) return 0;
        if (w[0] == w[1] && w[1] == w[2]) {
            return switch (w[0]) {
                case SYM_HIGH -> ODD_HIGH;
                case SYM_MID -> ODD_MID;
                case SYM_LOW -> ODD_LOW;
                default -> 0;
            };
        }
        return ODD_ANY;
    }

    public static BlessingPage evaluatePage(int[] p) {
        int odd = evaluateOdd(p);
        return new BlessingPage(Arrays.copyOf(p, CELLS), middles(p), odd);
    }

    public static void requireBoard(int[] p) {
        if (p == null || p.length != CELLS) throw new IllegalArgumentException("board must be 9 cells");
        for (int v : p) {
            if (v < EMPTY || v > SYM_LOW) throw new IllegalArgumentException("illegal symbol " + v);
        }
    }

    public record BlessingPage(int[] p, int[] w, int odd) {
        public BlessingPage {
            requireBoard(p);
            if (w == null || w.length != 3) throw new IllegalArgumentException("w must be 3");
        }
    }
}
