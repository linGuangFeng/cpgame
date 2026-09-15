package com.cpgame.replica.beeworkshop;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

final class SessionState {
    double balance = 100000.0;
    RedisRoundRepository.Claimed active;
    int step;
    long roundId;
    double roundBet;
    int roundLevel;
    double cumulativeFreeWin;
    Map<String, Object> lastResponse;
    final List<Map<String, Object>> currentResults = new ArrayList<>();
    final Deque<Map<String, Object>> history = new ArrayDeque<>();
}
