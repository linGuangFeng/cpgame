package com.cpgame.coinmastergo.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class RoundPlan {
    public String roundKey;
    public String transferId;
    public String paidBid;
    public int betLevel;
    public BigDecimal betSize;
    public BigDecimal betAmount;
    public BigDecimal postDebitBalance;
    public BigDecimal totalWin = BigDecimal.ZERO;
    public long createdAt;
    public String scenario;
    public boolean claimed;
    public int deliveryIndex;
    public int stepIndex;
    public List<RoundDelivery> deliveries = new ArrayList<>();

    public RoundPlan() { }

    public boolean exhausted() { return deliveryIndex >= deliveries.size(); }
}
