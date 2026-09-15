package com.cpgame.batchd.treasurehunt.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class GameRuleCore {
    public static final int GAME_ID = 1810;
    public static final String RULES_HASH = "b4b91422b13dc7e2eef14ed1eea034b2e0e361385e00134c1f1ffe9001ebc409";
    public static final int[][] PAYLINES = {{0,3,7},{0,4,7},{0,4,8},{1,4,7},{1,4,8},{1,5,8},{1,5,9},{2,5,8},{2,5,9},{2,6,9}};
    public static final Map<Integer,Integer> PAYTABLE = Map.of(1,100,2,50,3,20,4,10,5,5,6,3,7,200);
    public static final int WILD = 7;
    public enum Mode { ORDINARY, TREASURE_HUNT, ALL_REELS_MULTIPLIER }
    public enum Outcome { LOSS, WIN, TREASURE_HUNT, ALL_REELS_MULTIPLIER }
    public record Step(int[] symbols) { public Step { if(symbols==null||symbols.length!=10) throw new IllegalArgumentException("board must contain 10 symbols"); symbols=symbols.clone(); } @Override public int[] symbols(){return symbols.clone();} }
    public record CompleteRound(Mode mode,List<Step> steps) { public CompleteRound { steps=List.copyOf(steps); if(steps.isEmpty()) throw new IllegalArgumentException("round has no step"); } }
    public record Win(int line,int odd,int wp) {}
    public record StepEvaluation(List<Win> wins,int lineUnits,int factor,int stepUnits) {}
    public record Evaluation(Outcome outcome,List<StepEvaluation> steps,int totalUnits) {}
    private GameRuleCore() {}
    public static Evaluation evaluate(CompleteRound round) {
        if(round.mode()==Mode.ORDINARY&&round.steps().size()!=1) throw new IllegalArgumentException("ordinary round must have one step");
        if(round.mode()==Mode.ALL_REELS_MULTIPLIER&&round.steps().size()!=1) throw new IllegalArgumentException("all-reels round must have one step");
        if(round.mode()==Mode.TREASURE_HUNT&&(round.steps().size()<2||round.steps().size()>9)) throw new IllegalArgumentException("treasure hunt must have 2..9 steps");
        List<StepEvaluation> out=new ArrayList<>(); int total=0;
        for(Step step:round.steps()){int[] b=step.symbols();validateSymbols(b);List<Win>wins=new ArrayList<>();int units=0;for(int i=0;i<PAYLINES.length;i++){int a=b[PAYLINES[i][0]],c=b[PAYLINES[i][1]],d=b[PAYLINES[i][2]],wp=winningSymbol(a,c,d);if(wp!=0){int odd=PAYTABLE.get(wp);wins.add(new Win(i+1,odd,wp));units+=odd;}}int factor=round.mode()==Mode.ALL_REELS_MULTIPLIER?10:1;out.add(new StepEvaluation(List.copyOf(wins),units,factor,units*factor));total+=units*factor;}
        if(round.mode()==Mode.TREASURE_HUNT){for(int i=0;i<out.size()-1;i++)if(out.get(i).lineUnits()!=0)throw new IllegalArgumentException("nonterminal treasure step must not win");if(out.get(out.size()-1).lineUnits()<=0)throw new IllegalArgumentException("terminal treasure step must win");}
        if(round.mode()==Mode.ALL_REELS_MULTIPLIER&&out.get(0).wins().size()!=10)throw new IllegalArgumentException("all-reels mode requires ten winning lines");
        Outcome outcome=switch(round.mode()){case TREASURE_HUNT->Outcome.TREASURE_HUNT;case ALL_REELS_MULTIPLIER->Outcome.ALL_REELS_MULTIPLIER;case ORDINARY->total==0?Outcome.LOSS:Outcome.WIN;};return new Evaluation(outcome,List.copyOf(out),total);
    }
    private static int winningSymbol(int a,int b,int c){int target=a==WILD?(b==WILD?c:b):a;if(target==WILD)return WILD;return matches(a,target)&&matches(b,target)&&matches(c,target)?target:0;}
    private static boolean matches(int value,int target){return value==target||value==WILD;}
    private static void validateSymbols(int[] board){for(int v:board)if(!PAYTABLE.containsKey(v))throw new IllegalArgumentException("invalid symbol "+v);}
    public static BigDecimal paidBet(BigDecimal bet,int level){return bet.multiply(BigDecimal.valueOf(level*10L)).setScale(2,RoundingMode.HALF_UP);}
    public static BigDecimal totalWin(BigDecimal bet,int level,int units){return bet.multiply(BigDecimal.valueOf((long)level*units)).setScale(2,RoundingMode.HALF_UP);}
    public static BigDecimal displayedOdds(int units){return BigDecimal.valueOf(units).divide(BigDecimal.TEN,2,RoundingMode.HALF_UP).stripTrailingZeros();}
}
