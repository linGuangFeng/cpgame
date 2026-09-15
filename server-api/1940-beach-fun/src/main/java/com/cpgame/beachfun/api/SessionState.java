package com.cpgame.beachfun.api;
import java.util.*;
final class SessionState {
    double balance=10000.00,betGold,roundStartBalance,freeWin,priorFreeWin;
    int level,deliveryIndex;long roundId;
    RedisRoundRepository.Claimed active;
    Map<String,Object>lastData;
    final List<Map<String,Object>>roundResponses=new ArrayList<>();
    final Deque<Map<String,Object>>history=new ArrayDeque<>();
}
