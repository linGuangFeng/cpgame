package com.cpgame.replica.crazygems;

import com.hd.pg.appapi.business.vo.cpgame.crazygems.CrazyGemsBoard;

/** One paid Crazy Gems round is a single 3×3 board plus minecart rpx. */
public record CompleteRoundFact(int v, String[] rskl, int rpx) {
    public static final int VERSION = 1;

    public CompleteRoundFact {
        if (v != VERSION) throw new IllegalArgumentException("unsupported fact version");
        rskl = rskl.clone();
        if (rskl.length != CrazyGemsBoard.CELLS) throw new IllegalArgumentException("rskl length");
    }

    public CrazyGemsBoard board() {
        return new CrazyGemsBoard(rskl, rpx);
    }
}
