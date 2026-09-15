package com.cpgame.fishinggo.server;

import com.cpgame.fishinggo.core.CompleteRound;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SessionState {
    BigDecimal balance = new BigDecimal("10000.00");
    CompleteRound active;
    int deliveryIndex;
    String transferId;
    long lastCreatedAt;
    Map<String, Object> lastSpin;
    int paidRounds;
    final Deque<Map<String, Object>> history = new ArrayDeque<>();
    final Map<String, Map<String, Object>> idempotency = new LinkedHashMap<>();
    final List<Map<String, Object>> activeSteps = new ArrayList<>();
}
