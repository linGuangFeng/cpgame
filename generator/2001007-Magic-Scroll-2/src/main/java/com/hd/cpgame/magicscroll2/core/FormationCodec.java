package com.hd.cpgame.magicscroll2.core;

public final class FormationCodec {
    public int[] decode(String formation) {
        if (formation == null) throw new IllegalArgumentException("formation is required");
        String[] tokens = formation.split(",", -1);
        if (tokens.length != GameConstants.CELL_COUNT) {
            throw new IllegalArgumentException("base formation must contain exactly 36 cells");
        }
        int[] raw = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            try {
                raw[i] = Integer.parseInt(tokens[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("formation cell " + i + " is not a decimal integer", e);
            }
            if (raw[i] < 0) throw new IllegalArgumentException("formation cell must be non-negative");
        }
        return raw;
    }

    public String encode(int[] raw) {
        if (raw == null || raw.length != GameConstants.CELL_COUNT) {
            throw new IllegalArgumentException("base formation must contain exactly 36 cells");
        }
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < raw.length; i++) {
            if (i > 0) value.append(',');
            value.append(raw[i]);
        }
        return value.toString();
    }

    public static int symbolId(int raw) { return raw % 100; }
    public static int multiplicity(int raw) {
        int exponent = raw / 1000;
        if (exponent < 0 || exponent > 20) throw new IllegalArgumentException("cell multiplicity exponent is invalid");
        return 1 << exponent;
    }
    public static int blockOrDirt(int raw) { return (raw % 1000) / 100; }
    public static int index(int column, int row) { return column * GameConstants.ENCODED_ROWS + row; }

    /**
     * Counts symbols that the original LineMgr/SlotResult projection can actually draw in one row.
     * EMPTY cells and symbols still covered by dirt/wood are deliberately excluded.
     */
    public int countFrontendVisibleSymbols(String formation, int row) {
        if (row < 0 || row >= GameConstants.ENCODED_ROWS) {
            throw new IllegalArgumentException("row is outside the encoded 6x6 board");
        }
        int[] raw = decode(formation);
        int visible = 0;
        for (int column = 0; column < GameConstants.COLUMNS; column++) {
            int cell = raw[index(column, row)];
            if (symbolId(cell) != GameConstants.EMPTY && blockOrDirt(cell) <= 0) visible++;
        }
        return visible;
    }
}
