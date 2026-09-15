package com.hd.pg.appapi.business.vo.cpgame.freedomday;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Freedom Day 的纯牌面模型。
 * <p>主盘为 6 列 x 5 行，prop 使用客户端要求的列优先索引；trl 是第 2~5 列上方的 4 个额外格。</p>
 */
public final class FreedomDayBoard {
    public static final int REEL_COUNT = 6;
    public static final int ROW_COUNT = 5;
    public static final int MAIN_SIZE = REEL_COUNT * ROW_COUNT;
    public static final int TOP_SIZE = 4;

    private final int[] prop;
    private final int[] trl;

    public FreedomDayBoard(int[] prop, int[] trl) {
        if (prop == null || prop.length != MAIN_SIZE) {
            throw new IllegalArgumentException("prop length must be 30");
        }
        if (trl == null || trl.length != TOP_SIZE) {
            throw new IllegalArgumentException("trl length must be 4");
        }
        validate(prop);
        validate(trl);
        this.prop = prop.clone();
        this.trl = trl.clone();
    }

    private static void validate(int[] symbols) {
        for (int symbol : symbols) {
            if (symbol < 1 || symbol > 13) {
                throw new IllegalArgumentException("symbol must be between 1 and 13: " + symbol);
            }
        }
    }

    public int[] getProp() { return prop.clone(); }
    public int[] getTrl() { return trl.clone(); }
    public int getMain(int reel, int row) { return prop[reel * ROW_COUNT + row]; }
    public int getTop(int topIndex) { return trl[topIndex]; }

    public List<Position> positionsOnReel(int reel) {
        if (reel < 0 || reel >= REEL_COUNT) {
            throw new IllegalArgumentException("invalid reel: " + reel);
        }
        List<Position> result = new ArrayList<>();
        for (int row = 0; row < ROW_COUNT; row++) {
            int index = reel * ROW_COUNT + row;
            result.add(Position.main(index, prop[index]));
        }
        if (reel >= 1 && reel <= 4) {
            int topIndex = reel - 1;
            result.add(Position.top(topIndex, trl[topIndex]));
        }
        return Collections.unmodifiableList(result);
    }

    public static final class Position {
        private final boolean top;
        private final int index;
        private final int symbol;

        private Position(boolean top, int index, int symbol) {
            this.top = top;
            this.index = index;
            this.symbol = symbol;
        }
        public static Position main(int index, int symbol) { return new Position(false, index, symbol); }
        public static Position top(int index, int symbol) { return new Position(true, index, symbol); }
        public boolean isTop() { return top; }
        public int getIndex() { return index; }
        public int getSymbol() { return symbol; }
    }
}
