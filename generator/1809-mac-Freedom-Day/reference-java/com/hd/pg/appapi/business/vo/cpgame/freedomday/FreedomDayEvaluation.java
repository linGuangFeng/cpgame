package com.hd.pg.appapi.business.vo.cpgame.freedomday;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/** 由牌面确定的判奖结果，不包含随机行为。 */
public final class FreedomDayEvaluation {
    private final List<FreedomDayWin> wins;
    private final BigDecimal totalMultiplier;
    private final BigDecimal totalWin;
    private final int scatterCount;
    private final int awardedFreeSpins;
    private final int multiplier;

    public FreedomDayEvaluation(List<FreedomDayWin> wins, BigDecimal totalMultiplier, BigDecimal totalWin,
                                int scatterCount, int awardedFreeSpins, int multiplier) {
        this.wins = Collections.unmodifiableList(wins);
        this.totalMultiplier = totalMultiplier;
        this.totalWin = totalWin;
        this.scatterCount = scatterCount;
        this.awardedFreeSpins = awardedFreeSpins;
        this.multiplier = multiplier;
    }
    public List<FreedomDayWin> getWins() { return wins; }
    public BigDecimal getTotalMultiplier() { return totalMultiplier; }
    public BigDecimal getTotalWin() { return totalWin; }
    public int getScatterCount() { return scatterCount; }
    public int getAwardedFreeSpins() { return awardedFreeSpins; }
    public int getMultiplier() { return multiplier; }
}
