package com.hd.cpgame.riocarnival.core;

import java.math.BigDecimal;
import java.util.List;

public final class SpinStep {
    public BigDecimal ba;
    public int fsn;
    public int gt;
    public int nfsc;
    public int rpx;
    public List<String> rskl;
    public BigDecimal rwa;
    public int small_game_type;
    public int ss;
    public BigDecimal wa;
    public Object wmkl;

    public SpinStep() {}

    public boolean terminal() {
        return ss == 1 && fsn == nfsc;
    }
}
