package com.cpgame.fiesta.controller;

import com.cpgame.fiesta.GameRound;
import java.util.*;

final class SessionState {
    double balance=100000.0;
    GameRound active;
    int deliveryIndex;
    long roundId;
    final Deque<Map<String,Object>> history=new ArrayDeque<>();
    final List<Map<String,Object>> currentDeliveries=new ArrayList<>();
}

