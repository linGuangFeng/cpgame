package com.cpgame.wukong.core;

import java.util.List;

/** 独立结果复核器；生成器不能提供期望倍率。 */
public final class ResultUtil {
    private final GameRuleCore rules;
    public ResultUtil(GameRuleCore rules){this.rules=rules;}
    public Evaluation evaluate(CompleteRound round){rules.validate(round);return evaluateValidated(round);}
    Evaluation evaluateValidated(CompleteRound round){
        int base=concatenate(round.initial());
        return switch(round.mode()){
            case NONE -> new Evaluation(base,0,base,base,List.of(base));
            case X2 -> new Evaluation(base,0,base*2,base*2,List.of(base*2));
            case X5 -> new Evaluation(base,0,base*5,base*5,List.of(base*5));
            case RESPIN -> {int second=concatenate(round.respin());yield new Evaluation(base,second,base,base+second,List.of(base,second));}
        };
    }
    public static int concatenate(CompleteRound.ReelPair pair){String digits=("null".equals(pair.left())?"":pair.left())+("null".equals(pair.right())?"":pair.right());return digits.isEmpty()?0:Integer.parseInt(digits);}
    public record Evaluation(int baseMultiplier,int respinMultiplier,int chessWinMultiplier,int totalMultiplier,List<Integer> resultMultipliers){}
}
