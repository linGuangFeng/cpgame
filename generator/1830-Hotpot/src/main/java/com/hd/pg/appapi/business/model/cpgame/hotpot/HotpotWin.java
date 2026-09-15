package com.hd.pg.appapi.business.model.cpgame.hotpot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One count-anywhere combination. Fields map to win_arr.p / n / odd. */
public final class HotpotWin {
    private final int symbol;
    private final int count;
    private final int odd;
    private final List<Integer> positions;

    public HotpotWin(int symbol, int count, int odd, List<Integer> positions) {
        this.symbol = symbol;
        this.count = count;
        this.odd = odd;
        this.positions = Collections.unmodifiableList(new ArrayList<>(positions));
    }

    public int getSymbol() { return symbol; }
    public int getCount() { return count; }
    public int getOdd() { return odd; }
    public List<Integer> getPositions() { return positions; }
}
