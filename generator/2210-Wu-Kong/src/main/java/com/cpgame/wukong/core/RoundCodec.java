package com.cpgame.wukong.core;

import java.nio.charset.StandardCharsets;

/** 仅保存不可从规则重算的最小 ASCII 完整局事实。 */
public final class RoundCodec {
    public static final String VERSION="WK46R2";
    private final GameRuleCore rules; private final ResultUtil results;
    public RoundCodec(GameRuleCore rules){this.rules=rules;this.results=new ResultUtil(rules);}
    public String encode(CompleteRound round){
        rules.validate(round);String mode=switch(round.mode()){case NONE->"N";case X2->"2";case X5->"5";case RESPIN->"R";};
        String value=VERSION+'|'+mode+'|'+token(round.initial().left())+'|'+token(round.initial().right())+'|'+(round.respin()==null?"-":token(round.respin().left()))+'|'+(round.respin()==null?"-":token(round.respin().right()));
        if(!StandardCharsets.US_ASCII.newEncoder().canEncode(value)||value.startsWith("{")||value.startsWith("["))throw new IllegalStateException("member must be non-JSON ASCII");return value;
    }
    public CompleteRound decode(String value){
        if(value==null||value.startsWith("{")||value.startsWith("["))throw new IllegalArgumentException("JSON member forbidden");String[] p=value.split("\\|",-1);if(p.length!=6||!VERSION.equals(p[0]))throw new IllegalArgumentException("invalid codec member");
        CompleteRound.Mode mode=switch(p[1]){case"N"->CompleteRound.Mode.NONE;case"2"->CompleteRound.Mode.X2;case"5"->CompleteRound.Mode.X5;case"R"->CompleteRound.Mode.RESPIN;default->throw new IllegalArgumentException("unknown mode");};
        CompleteRound round=new CompleteRound(mode,new CompleteRound.ReelPair(untoken(p[2]),untoken(p[3])),mode==CompleteRound.Mode.RESPIN?new CompleteRound.ReelPair(untoken(p[4]),untoken(p[5])):null);rules.validate(round);return round;
    }
    public int verifyMultiplier(String member){return results.evaluate(decode(member)).totalMultiplier();}
    private static String token(String v){return "null".equals(v)?"~":v;} private static String untoken(String v){return "~".equals(v)?"null":v;}
}
