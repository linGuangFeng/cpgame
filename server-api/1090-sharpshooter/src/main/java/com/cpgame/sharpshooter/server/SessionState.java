package com.cpgame.sharpshooter.server;
import com.cpgame.sharpshooter.core.CompleteRound;import java.util.*;
final class SessionState {
 double balance=1_000_000.00,paidBet,cumulativeFreeWin,betSize=.02;long roundId;int spinIndex,betLevel=10;
 String activeMember;CompleteRound activeRound;Map<String,Object> lastResult;
 final List<Map<String,Object>> activeResults=new ArrayList<>();final Deque<Map<String,Object>> history=new ArrayDeque<>();
}
