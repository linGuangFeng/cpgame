package com.hd.cpgame.riocarnival.server.state;

import com.hd.cpgame.riocarnival.core.GeneratedRound;
import com.hd.cpgame.riocarnival.core.SpinStep;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GameSession {
    public String sessionKey;
    public String launchHash;
    public List<String> tokenHashes = new ArrayList<String>();
    public long playerId;
    public BigDecimal balance;
    public GeneratedRound activeRound;
    public int deliveryIndex;
    public SpinStep lastDelivered;
    public BigDecimal activeBalanceAfterBet;
    public List<HistoryRecord> history = new ArrayList<HistoryRecord>();
    public Map<String, DeliveryReceipt> idempotency = new LinkedHashMap<String, DeliveryReceipt>();
    public GameSession() {}
}
