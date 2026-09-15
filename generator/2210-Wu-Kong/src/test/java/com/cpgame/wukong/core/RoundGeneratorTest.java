package com.cpgame.wukong.core;

import static org.junit.jupiter.api.Assertions.*;
import java.security.SecureRandom;
import java.util.*;
import org.junit.jupiter.api.Test;

class RoundGeneratorTest {
    @Test void tenThousandFreshRoundsRespectJointStatesAndCaps(){Properties p=properties();RoundGenerator g=new RoundGenerator(new SecureRandom(),p);GameRuleCore rules=new GameRuleCore();ResultUtil results=new ResultUtil(rules);EnumSet<CompleteRound.Outcome> seen=EnumSet.noneOf(CompleteRound.Outcome.class);for(int i=0;i<10000;i++){CompleteRound r=g.generate();rules.validateHardCaps(r);assertTrue(results.evaluate(r).totalMultiplier()>=0);seen.add(rules.classify(r));}assertEquals(EnumSet.allOf(CompleteRound.Outcome.class),seen);}
    @Test void rejectsMissingOrZeroDirectSymbolWeight(){Properties missing=properties();missing.remove("generation.symbol.initial.blank");assertThrows(IllegalArgumentException.class,()->new RoundGenerator(new SecureRandom(),missing));Properties zero=properties();zero.setProperty("generation.symbol.respin-redeal.ten","0");assertThrows(IllegalArgumentException.class,()->new RoundGenerator(new SecureRandom(),zero));}
    private static Properties properties(){
        Properties p=new Properties();
        p.setProperty("generation.symbol.initial.blank","1538");p.setProperty("generation.symbol.initial.zero","201");p.setProperty("generation.symbol.initial.one","51");p.setProperty("generation.symbol.initial.five","129");p.setProperty("generation.symbol.initial.ten","81");
        p.setProperty("generation.symbol.respin-redeal.zero","8");p.setProperty("generation.symbol.respin-redeal.one","10");p.setProperty("generation.symbol.respin-redeal.five","21");p.setProperty("generation.symbol.respin-redeal.ten","17");
        p.setProperty("weights.mode","NONE:804|X2:75|X5:93|RESPIN:28");p.setProperty("weights.initial.none","null,null:621|null,0:132|10,1:12|10,0:11|10,5:10|5,0:10|5,1:5|5,5:3");p.setProperty("weights.initial.x2","null,null:24|10,5:14|10,0:13|10,1:8|5,5:8|5,1:7|5,0:1");p.setProperty("weights.initial.x5","null,null:34|5,5:13|10,0:12|5,0:11|1,5:8|5,1:8|null,0:6|10,null:1");p.setProperty("weights.initial.respin","null,null:15|null,0:5|null,1:3|5,5:2|null,5:2|5,null:1");p.setProperty("weights.respin-redeal","10,1:9|10,5:5|5,0:5|5,5:5|10,0:3|5,1:1");
        return p;
    }
}
