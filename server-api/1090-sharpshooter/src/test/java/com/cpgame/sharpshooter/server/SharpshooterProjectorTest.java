package com.cpgame.sharpshooter.server;
import com.cpgame.sharpshooter.core.*;import org.junit.jupiter.api.*;import java.security.SecureRandom;import java.util.*;
class SharpshooterProjectorTest {
 @Test void specialProjectionKeepsCountersAndCompletes(){GameRuleCore r=new GameRuleCore();CompleteRound round=new RoundGenerator(new SecureRandom(),r).freeSpins();SessionState s=new SessionState();s.paidBet=4;s.roundId=100;SharpshooterProjector p=new SharpshooterProjector();for(int i=0;i<round.spins().size();i++){Map<String,Object>d=p.project(s,round,i,.02,10);Assertions.assertEquals(i==0?1:2,d.get("type"));@SuppressWarnings("unchecked") Map<String,Object> f=(Map<String,Object>)d.get("frees");Assertions.assertEquals(round.spins().get(i).freeRemaining(),f.get("surplus_times"));}}
}
