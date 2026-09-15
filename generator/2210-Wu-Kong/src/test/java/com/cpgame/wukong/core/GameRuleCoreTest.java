package com.cpgame.wukong.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class GameRuleCoreTest {
    private final GameRuleCore rules=new GameRuleCore();private final ResultUtil results=new ResultUtil(rules);private final RoundCodec codec=new RoundCodec(rules);
    @Test void providerRulesAndExplicitPartition(){
        var loss=new CompleteRound(CompleteRound.Mode.NONE,new CompleteRound.ReelPair("null","null"),null);assertEquals(CompleteRound.Outcome.ORDINARY_LOSS,rules.classify(loss));assertEquals(0,results.evaluate(loss).totalMultiplier());
        var win=new CompleteRound(CompleteRound.Mode.NONE,new CompleteRound.ReelPair("10","5"),null);assertEquals(105,results.evaluate(win).totalMultiplier());
        var x2=new CompleteRound(CompleteRound.Mode.X2,new CompleteRound.ReelPair("10","1"),null);assertEquals(202,results.evaluate(x2).totalMultiplier());
        var x5=new CompleteRound(CompleteRound.Mode.X5,new CompleteRound.ReelPair("5","5"),null);assertEquals(275,results.evaluate(x5).totalMultiplier());
        var respin=new CompleteRound(CompleteRound.Mode.RESPIN,new CompleteRound.ReelPair("null","0"),new CompleteRound.ReelPair("10","5"));assertEquals(105,results.evaluate(respin).totalMultiplier());assertEquals(2,results.evaluate(respin).resultMultipliers().size());
        assertThrows(IllegalArgumentException.class,()->new CompleteRound(CompleteRound.Mode.NONE,new CompleteRound.ReelPair("null","null"),new CompleteRound.ReelPair("5","0")));
        assertThrows(IllegalArgumentException.class,()->rules.validate(new CompleteRound(CompleteRound.Mode.X2,new CompleteRound.ReelPair("0","1"),null)));
    }
    @Test void minimalCodecRoundTripsAllFacts(){var round=new CompleteRound(CompleteRound.Mode.RESPIN,new CompleteRound.ReelPair("null","1"),new CompleteRound.ReelPair("10","5"));String member=codec.encode(round);assertTrue(member.matches("[\\x20-\\x7E]+"));assertFalse(member.startsWith("{"));assertEquals(round,codec.decode(member));assertEquals(106,codec.verifyMultiplier(member));}
    @Test void rulesHashIsCanonical(){assertEquals("8815bbc4ff5dd45c7ed5d2ccc1d0891183ce5f109f981594ce944a681a9c1516",GameRuleCore.RULES_HASH);}
}
