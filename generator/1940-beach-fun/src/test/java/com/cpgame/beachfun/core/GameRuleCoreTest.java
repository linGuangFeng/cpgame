package com.cpgame.beachfun.core;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class GameRuleCoreTest{
 @Test void knownWaysAndPaytable(){var core=new GameRuleCore();int[]b={8,1,2,3,8,4,5,6,8,7,1,2,3,4,5,6,7,8,1,2};var wins=core.evaluate(b,1);assertTrue(wins.stream().anyMatch(w->w.symbol()==8&&w.reels()==3&&w.ways()==1&&w.odds()==15));}
 @Test void allModesProduceVerifiedCompleteRounds(){var f=new RoundFactory(new GameRuleCore());var v=new RoundVerifier();for(var mode:RoundFactory.Requested.values())for(int i=0;i<20;i++)v.verify(f.generate(mode));}
 @Test void codecIsAsciiMinimalAndRoundTripsFacts(){var rules=new GameRuleCore();var round=new RoundFactory(rules).generate(RoundFactory.Requested.FREE);var codec=new MinimalRoundFactCodec();String s=codec.encode(round);assertTrue(s.chars().allMatch(c->c>=32&&c<=126));var decoded=codec.decode(s);rules.validate(decoded);assertEquals(round.totalUnits(),decoded.totalUnits());assertEquals(round.deliveries().size(),decoded.deliveries().size());}
}
