package com.cpgame.coinmastergo.model;

public class HistoryRecord {
    public RoundPlan round;
    public long completedAt;

    public HistoryRecord() { }
    public HistoryRecord(RoundPlan round, long completedAt) {
        this.round = round;
        this.completedAt = completedAt;
    }
}
