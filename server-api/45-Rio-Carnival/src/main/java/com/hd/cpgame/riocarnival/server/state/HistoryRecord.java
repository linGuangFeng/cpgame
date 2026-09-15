package com.hd.cpgame.riocarnival.server.state;

import com.hd.cpgame.riocarnival.core.GeneratedRound;
import java.math.BigDecimal;

public final class HistoryRecord {
    public GeneratedRound round;
    public BigDecimal balanceAfterBet;
    public long completedAt;
    public HistoryRecord() {}
    public HistoryRecord(GeneratedRound round, BigDecimal balanceAfterBet, long completedAt) {
        this.round=round; this.balanceAfterBet=balanceAfterBet; this.completedAt=completedAt;
    }
}
