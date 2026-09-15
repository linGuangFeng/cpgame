package com.cpgame.g2110.server;
import com.cpgame.g2110.core.GameRuleCore;import java.util.*;
final class SessionState{double balance=100000.0;RedisRoundRepository.Claimed active;int step;long roundId;double roundBet;int roundLevel;double cumulativeFreeWin;Map<String,Object>lastResponse;final List<Map<String,Object>>currentResults=new ArrayList<>();final Deque<Map<String,Object>>history=new ArrayDeque<>();}
