package com.cpgame.luckywheel.api;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SessionState implements Serializable {
    private final String launchKey;
    private final String token;
    private BigDecimal balance = new BigDecimal("10000.00");
    private long transferSequence;
    private final List<HistoryRecord> history = new ArrayList<>();
    private final Map<String, Integer> claimedRoundDelivery = new LinkedHashMap<>();
    private final Map<String, String> idempotentResponses = new LinkedHashMap<>();
    SessionState(String launchKey, String token) { this.launchKey = launchKey; this.token = token; }
    public String launchKey() { return launchKey; }
    public String token() { return token; }
    public BigDecimal balance() { return balance; }
    public void balance(BigDecimal value) { balance = value; }
    public List<HistoryRecord> history() { return history; }
    public Map<String, Integer> claimedRoundDelivery() { return claimedRoundDelivery; }
    public Map<String, String> idempotentResponses() { return idempotentResponses; }
    public long nextTransferSequence() { return ++transferSequence; }
}
