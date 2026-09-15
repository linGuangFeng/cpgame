package com.cpgame.sharpshooter.core;
import org.junit.jupiter.api.*;import java.security.SecureRandom;
class GameRuleCoreTest {
 @Test void generatedRoundsValidateAndCodecRoundTrips(){GameRuleCore r=new GameRuleCore();RoundGenerator g=new RoundGenerator(new SecureRandom(),r);RoundCodec c=new RoundCodec();for(int i=0;i<100;i++){CompleteRound x=i%3==0?g.freeSpins():g.ordinary(i%2==0);r.validateRound(x);Assertions.assertEquals(c.encode(x),c.encode(c.decode(c.encode(x))));}}
 @Test void knownWaysPay(){GameRuleCore r=new GameRuleCore();int[] b={1,2,3,4,1,5,6,7,1,8,8,8,2,3,4,5,2,3,4,5};Assertions.assertEquals(1,r.evaluate(b).size());Assertions.assertEquals(2,r.evaluate(b).get(0).payoutUnits());}
}
