package com.cpgame.hiddenrealm.server;

import com.cpgame.hiddenrealm.core.CompleteRound;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

final class SessionState {
    BigDecimal balance = new BigDecimal("1000000.00");
    BigDecimal betSize = new BigDecimal("0.05");
    int betLevel = 10;
    int deliveryIndex;
    long roundId;
    String activeMember;
    CompleteRound activeRound;
    Map<String, Object> lastResult;
    final List<Map<String, Object>> activeResults = new ArrayList<>();
    final Deque<Map<String, Object>> history = new ArrayDeque<>();
}
