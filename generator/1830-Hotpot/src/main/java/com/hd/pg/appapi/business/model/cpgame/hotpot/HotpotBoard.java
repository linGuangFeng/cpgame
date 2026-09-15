package com.hd.pg.appapi.business.model.cpgame.hotpot;

/**
 * 6x6 visible board, column-major. Index 0 in a column is visual top, index 5 is visual bottom.
 * No extra buffer cells. No merged grids.
 */
public final class HotpotBoard {
    public static final int COLUMNS = 6;
    public static final int ROWS = 6;
    public static final int SIZE = COLUMNS * ROWS;
    public static final int MIN_SYMBOL = 1;
    public static final int MAX_SYMBOL = 23;

    private final int[] prop;

    public HotpotBoard(int[] prop) {
        if (prop == null || prop.length != SIZE) {
            throw new IllegalArgumentException("prop length must be 36");
        }
        this.prop = prop.clone();
        for (int symbol : this.prop) {
            if (symbol < MIN_SYMBOL || symbol > MAX_SYMBOL) {
                throw new IllegalArgumentException("symbol out of range 1..23: " + symbol);
            }
        }
    }

    public int[] getProp() {
        return prop.clone();
    }

    public int get(int column, int row) {
        if (column < 0 || column >= COLUMNS || row < 0 || row >= ROWS) {
            throw new IllegalArgumentException("cell out of board");
        }
        return prop[column * ROWS + row];
    }

    public int index(int column, int row) {
        return column * ROWS + row;
    }
}
