package com.hd.cpgame.riocarnival.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class GeneratedRound {
    public String roundKey;
    public long createdAt;
    public int betLevel;
    public BigDecimal betSize;
    public List<SpinStep> steps = new ArrayList<SpinStep>();

    public GeneratedRound() {}

    public boolean terminal() {
        return !steps.isEmpty() && steps.get(steps.size()-1).terminal();
    }

    public BigDecimal totalBet() {
        return betSize.multiply(BigDecimal.valueOf(betLevel)).multiply(BigDecimal.valueOf(GameRules.PAYLINE_COUNT));
    }
}
