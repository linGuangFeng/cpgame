package com.hd.pg.appapi.business.model.cpgame.hotpot;

import java.util.Collections;
import java.util.List;

/** Deterministic page evaluation. No random, no Redis, no money fields. */
public final class HotpotEvaluation {
    private final List<HotpotWin> wins;
    private final int oddSum;
    private final int scatterCount;
    private final int multiplierSum;
    private final HotpotPageKind pageKind;

    public HotpotEvaluation(List<HotpotWin> wins, int oddSum, int scatterCount, int multiplierSum,
                            HotpotPageKind pageKind) {
        this.wins = Collections.unmodifiableList(wins);
        this.oddSum = oddSum;
        this.scatterCount = scatterCount;
        this.multiplierSum = multiplierSum;
        this.pageKind = pageKind;
    }

    public List<HotpotWin> getWins() { return wins; }
    public int getOddSum() { return oddSum; }
    public int getScatterCount() { return scatterCount; }
    public int getMultiplierSum() { return multiplierSum; }
    public HotpotPageKind getPageKind() { return pageKind; }
}
