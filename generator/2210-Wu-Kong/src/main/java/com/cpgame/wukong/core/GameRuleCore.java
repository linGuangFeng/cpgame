package com.cpgame.wukong.core;

import java.util.List;
import java.util.Set;

/** 2210 唯一玩法核心：显式互斥状态、位置域和硬上限。 */
public final class GameRuleCore {
    public static final int GAME_ID=2210;
    public static final String RULES_VERSION="WUKONG_V46_REBUILD_20260907";
    public static final String RULES_HASH="8815bbc4ff5dd45c7ed5d2ccc1d0891183ce5f109f981594ce944a681a9c1516";
    public static final List<String> LANGUAGES=List.of("bn","en","es","fr","id","ko","pt-br","th","tr","vi");
    private static final Set<String> INITIAL_LEFT=Set.of("null","1","5","10");
    private static final Set<String> INITIAL_RIGHT=Set.of("null","0","1","5");
    private static final Set<String> RESPIN_LEFT=Set.of("5","10");
    private static final Set<String> RESPIN_RIGHT=Set.of("0","1","5");

    public void validate(CompleteRound round){
        require(round.initial(),INITIAL_LEFT,INITIAL_RIGHT,"initial");
        switch(round.mode()){
            case NONE,X2,X5 -> {if(round.respin()!=null)throw new IllegalArgumentException("respin present outside RESPIN mode");}
            case RESPIN -> require(round.respin(),RESPIN_LEFT,RESPIN_RIGHT,"respin");
        }
        validateHardCaps(round);
    }
    public void validateHardCaps(CompleteRound round){
        if(round.initial()==null)throw new IllegalArgumentException("initial must contain exactly two ordered positions");
        if(round.mode()==CompleteRound.Mode.RESPIN&&round.respin()==null)throw new IllegalArgumentException("respin must contain exactly two new positions");
    }
    public CompleteRound.Outcome classify(CompleteRound round){
        validate(round); int total=new ResultUtil(this).evaluateValidated(round).totalMultiplier();
        return switch(round.mode()){
            case NONE -> total==0?CompleteRound.Outcome.ORDINARY_LOSS:CompleteRound.Outcome.ORDINARY_WIN;
            case X2 -> CompleteRound.Outcome.SPECIAL_X2;
            case X5 -> CompleteRound.Outcome.SPECIAL_X5;
            case RESPIN -> CompleteRound.Outcome.SPECIAL_RESPIN;
        };
    }
    public boolean isSpecial(CompleteRound round){return switch(classify(round)){case ORDINARY_LOSS,ORDINARY_WIN->false;case SPECIAL_X2,SPECIAL_X5,SPECIAL_RESPIN->true;};}
    private static void require(CompleteRound.ReelPair pair,Set<String> left,Set<String> right,String name){if(pair==null||!left.contains(pair.left())||!right.contains(pair.right()))throw new IllegalArgumentException(name+" violates observed position domain: "+pair);}
}
