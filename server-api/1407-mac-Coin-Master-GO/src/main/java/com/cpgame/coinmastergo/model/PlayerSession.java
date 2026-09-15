package com.cpgame.coinmastergo.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PlayerSession {
    public String token;
    public String launchToken;
    public long playerId;
    public BigDecimal balance;
    public RoundPlan activeRound;
    public SpinStep lastStep;
    public List<HistoryRecord> history = new ArrayList<>();
    public Set<String> claimedRoundKeys = new LinkedHashSet<>();
    public Map<String, SpinStep> idempotentSpinResponses = new LinkedHashMap<>();
    public long paidRoundSequence;

    public PlayerSession() { }
}
