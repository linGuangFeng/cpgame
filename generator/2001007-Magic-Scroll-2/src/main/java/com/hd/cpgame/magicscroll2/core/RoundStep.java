package com.hd.cpgame.magicscroll2.core;

import java.io.Serializable;
import java.math.BigDecimal;

public final class RoundStep implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String formation;
    private final int activeRows;
    private final int globalMultiplier;
    private final BigDecimal stepWin;
    private final BigDecimal cumulativeWin;
    private final boolean terminal;

    public RoundStep(String formation, int activeRows, int globalMultiplier, BigDecimal stepWin,
                     BigDecimal cumulativeWin, boolean terminal) {
        this.formation = formation;
        this.activeRows = activeRows;
        this.globalMultiplier = globalMultiplier;
        this.stepWin = stepWin;
        this.cumulativeWin = cumulativeWin;
        this.terminal = terminal;
    }

    public String getFormation() { return formation; }
    public int getActiveRows() { return activeRows; }
    public int getGlobalMultiplier() { return globalMultiplier; }
    public BigDecimal getStepWin() { return stepWin; }
    public BigDecimal getCumulativeWin() { return cumulativeWin; }
    public boolean isTerminal() { return terminal; }
}
