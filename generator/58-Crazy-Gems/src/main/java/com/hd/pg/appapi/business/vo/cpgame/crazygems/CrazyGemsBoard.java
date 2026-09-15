package com.hd.pg.appapi.business.vo.cpgame.crazygems;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Visible spin state for Crazy Gems: 3×3 reel-major {@code rskl} plus minecart {@code rpx}.
 * The frontend 4th column only displays rpx; it is not a paying reel.
 */
public final class CrazyGemsBoard {
    public static final int REELS = 3;
    public static final int ROWS = 3;
    public static final int CELLS = 9;
    public static final String WILD = "WILD";
    public static final List<String> SYMBOLS = List.of("WILD", "H1", "H2", "H3", "H4", "H5", "H6", "H7");
    public static final Set<String> SYMBOL_SET = Set.copyOf(SYMBOLS);
    public static final int[] RPX_VALUES = {1, 2, 3, 5, 10, 15};
    /** Row index per reel for the five fixed paylines. */
    public static final int[][] PAYLINES = {
            {0, 0, 0},
            {1, 1, 1},
            {2, 2, 2},
            {0, 1, 2},
            {2, 1, 0}
    };

    private final String[] rskl;
    private final int rpx;

    public CrazyGemsBoard(String[] rskl, int rpx) {
        if (rskl == null || rskl.length != CELLS) {
            throw new IllegalArgumentException("rskl must contain 9 symbols");
        }
        String[] copy = new String[CELLS];
        for (int i = 0; i < CELLS; i++) {
            String symbol = rskl[i];
            if (symbol == null || !SYMBOL_SET.contains(symbol)) {
                throw new IllegalArgumentException("unknown symbol: " + symbol);
            }
            copy[i] = symbol;
        }
        if (!legalRpx(rpx)) throw new IllegalArgumentException("illegal rpx: " + rpx);
        this.rskl = copy;
        this.rpx = rpx;
    }

    public static boolean legalRpx(int rpx) {
        for (int value : RPX_VALUES) if (value == rpx) return true;
        return false;
    }

    public String symbol(int reel, int row) {
        return rskl[reel * ROWS + row];
    }

    public String[] rskl() {
        return rskl.clone();
    }

    public List<String> rsklList() {
        return List.of(rskl);
    }

    public int rpx() {
        return rpx;
    }

    public boolean minecartSpecial() {
        return rpx > 1;
    }

    @Override
    public String toString() {
        return "CrazyGemsBoard{rskl=" + Arrays.toString(rskl) + ", rpx=" + rpx + "}";
    }
}
