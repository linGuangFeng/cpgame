package com.cpgame.g1910.server;

import com.cpgame.g1910.core.GameRuleCore;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ProviderPresenter {
    private ProviderPresenter() { }
    static Map<String,Object> paid(GameRuleCore.CompleteRound round,BigDecimal bet,int level,BigDecimal start,String oid){
        var evaluation=GameRuleCore.evaluate(round);BigDecimal cost=GameRuleCore.paidBet(bet,level),award=GameRuleCore.award(bet,level,evaluation.paid().units());
        boolean trigger=round.mode()==GameRuleCore.Mode.FREE_REWARD;int small=round.mode()==GameRuleCore.Mode.SMALL_GAME_1?1:0;
        return response(round.paid(),evaluation.paid(),bet,level,start,start.subtract(cost).add(award),award.subtract(cost),oid,1,small,
            frees(cost,bet,level,1,trigger?1:0,trigger?1:0,GameRuleCore.scatterCount(round.paid().symbols()),0,0,0,0),null);
    }
    static Map<String,Object> free(GameRuleCore.CompleteRound round,int index,BigDecimal bet,int level,BigDecimal start,String oid,Map<String,Object>results){
        var evaluation=GameRuleCore.evaluate(round);BigDecimal cost=GameRuleCore.paidBet(bet,level),before=start.subtract(cost),cumulative=BigDecimal.ZERO;
        for(int i=0;i<index;i++)cumulative=cumulative.add(GameRuleCore.award(bet,level,evaluation.freeSteps().get(i).units()));
        var stepEvaluation=evaluation.freeSteps().get(index);BigDecimal award=GameRuleCore.award(bet,level,stepEvaluation.units());BigDecimal stepStart=before.add(cumulative),stepEnd=stepStart.add(award),twa=cumulative.add(award);
        int effectiveTotal=round.freeTimes();for(int i=0;i<=index;i++)if(evaluation.freeSteps().get(i).scatterCount()>=3)effectiveTotal+=GameRuleCore.RETRIGGER_FREE_STEPS;
        return response(round.freeSteps().get(index),stepEvaluation,bet,level,stepStart,stepEnd,award,oid,2,2,
            frees(cost,bet,level,round.freeMultiplier(),0,0,GameRuleCore.scatterCount(round.paid().symbols()),effectiveTotal-index-1,effectiveTotal,twa,1),results);
    }
    static Map<String,Object> init(BigDecimal balance){
        int[] board={1,3,7,4,8,10,2,5,9,6,11,3,7,4,8};var step=new GameRuleCore.Step(board);var evaluation=GameRuleCore.evaluateStep(step,1);
        return response(step,evaluation,new BigDecimal("0.02"),1,balance,balance,BigDecimal.ZERO,"init-1910",1,0,frees(BigDecimal.ZERO,BigDecimal.ZERO,0,1,0,0,0,0,0,BigDecimal.ZERO,0),null);
    }
    static Map<String,Object> selector(GameRuleCore.CompleteRound round,boolean timeSelected,boolean multiplierSelected,int timeIndex,int multiplierIndex,String oid){
        int scatters=GameRuleCore.scatterCount(round.paid().symbols());int[] time=scatters==4?new int[]{12,16,24}:new int[]{8,12,20};int[] multipliers={2,5,8};
        rotateValueTo(time,round.freeTimes(),timeIndex);rotateValueTo(multipliers,round.freeMultiplier(),multiplierIndex);
        Map<String,Object> results=new LinkedHashMap<>();results.put("multiple",selection(multiplierIndex,multipliers,round.freeMultiplier()));results.put("time",selection(timeIndex,time,round.freeTimes()));
        Map<String,Object> out=new LinkedHashMap<>();out.put("frees",frees(null,null,null,multiplierSelected?round.freeMultiplier():1,timeSelected?0:1,multiplierSelected?0:1,scatters,round.freeTimes(),round.freeTimes(),BigDecimal.ZERO,timeSelected?1:0));out.put("oid",oid);out.put("results",results);return out;
    }
    static Map<String,Object> selection(int index,int[]prop,int value){Map<String,Object>out=new LinkedHashMap<>();out.put("index",Math.max(0,Math.min(2,index)));out.put("prop",ints(prop));out.put("rand",80);out.put("value",value);return out;}
    private static Map<String,Object> response(GameRuleCore.Step step,GameRuleCore.StepEvaluation evaluation,BigDecimal bet,int level,BigDecimal start,BigDecimal end,BigDecimal change,String oid,int type,int small,Map<String,Object>frees,Map<String,Object>results){
        BigDecimal paidBet=GameRuleCore.paidBet(bet,level),award=GameRuleCore.award(bet,level,evaluation.units());Map<String,Object>props=new LinkedHashMap<>();props.put("frees_prop",evaluation.scatterCount());props.put("prop",ints(step.symbols()));props.put("tw",award);props.put("win_arr",wins(evaluation,bet,level,type==2?((Number)frees.get("m")).intValue():1));
        Map<String,Object>out=new LinkedHashMap<>();out.put("bet",bet);out.put("bet_gold",paidBet);out.put("change_gold",change);out.put("end_gold",end);out.put("frees",frees);out.put("level",level);out.put("multiple_flag",frees.get("multiple_flag"));out.put("odds",GameRuleCore.displayedOdds(award,paidBet));out.put("oid",oid);out.put("props",props);if(results!=null)out.put("results",results);out.put("small_game_type",small);out.put("start_gold",start);out.put("time_flag",frees.get("time_flag"));out.put("total_win",award);out.put("type",type);return out;
    }
    private static List<Map<String,Object>>wins(GameRuleCore.StepEvaluation evaluation,BigDecimal bet,int level,int defMultiplier){List<Map<String,Object>>out=new ArrayList<>();for(var win:evaluation.wins()){Map<String,Object>row=new LinkedHashMap<>();row.put("def_mul",defMultiplier==1?0:defMultiplier);row.put("line",win.line());row.put("mul",win.multiplier());row.put("num",win.count());row.put("odd",win.odd());row.put("tw",GameRuleCore.award(bet,level,win.units()));row.put("wp",win.symbol());out.add(row);}return out;}
    private static Map<String,Object>frees(Object ba,Object bet,Object level,int multiplier,int timeFlag,int multipleFlag,int scatter,int st,int tt,Object twa,int timeType){Map<String,Object>out=new LinkedHashMap<>();out.put("ba",ba);out.put("bet",bet);out.put("l",level);out.put("m",multiplier);out.put("mul_type",multipleFlag==0&&multiplier>1?1:0);out.put("multiple_flag",multipleFlag);out.put("spe_num",scatter);out.put("st",st);out.put("time_flag",timeFlag);out.put("time_type",timeType);out.put("tt",tt);out.put("twa",twa);return out;}
    private static List<Integer>ints(int[]values){List<Integer>out=new ArrayList<>(values.length);for(int value:values)out.add(value);return out;}
    private static void rotateValueTo(int[]values,int selected,int index){int target=Math.max(0,Math.min(2,index)),found=0;for(int i=0;i<values.length;i++)if(values[i]==selected)found=i;int swap=values[target];values[target]=selected;values[found]=swap;}
}
