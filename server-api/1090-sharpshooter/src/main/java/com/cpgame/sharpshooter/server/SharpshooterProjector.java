package com.cpgame.sharpshooter.server;

import com.cpgame.sharpshooter.core.*;import java.util.*;

final class SharpshooterProjector {
 private final GameRuleCore rules=new GameRuleCore();
 Map<String,Object> project(SessionState session,CompleteRound round,int spinIndex,double betSize,int level){
  CompleteRound.Spin spin=round.spins().get(spinIndex);boolean special=round.spins().size()>1,paid=spin.paid();double totalBet=money(betSize*level*20),spinWin=spinWin(spin,betSize,level,spinIndex>0),before=session.balance;if(paid)session.balance=money(session.balance-totalBet);session.balance=money(session.balance+spinWin);if(spinIndex>0)session.cumulativeFreeWin=money(session.cumulativeFreeWin+spinWin);
  Map<String,Object> data=new LinkedHashMap<>();data.put("bet",betSize);data.put("bet_gold",paid?totalBet:0);data.put("big_win",spinWin>=totalBet*20?1:0);data.put("change_gold",money((paid?-totalBet:0)+spinWin));data.put("end_gold",session.balance);data.put("frees",frees(spin,special,betSize,totalBet,level,session.cumulativeFreeWin));data.put("gid",1090);data.put("is_gold_arr",goldArray(spin.cascades().get(0).gold()));data.put("level",level);data.put("new_free",Map.of("new_times",spin.newFree(),"nums",rules.scatterCount(spin.cascades().get(0).symbols()),"prop",9));data.put("odds",totalBet==0?0:money(spinWin/session.paidBet));data.put("oid",session.roundId+spinIndex);data.put("props",props(spin,betSize,level,spinIndex>0));data.put("small_game_type",special?2:0);data.put("start_gold",before);data.put("total_win",spinWin);data.put("type",paid?1:2);return data;
 }
 private List<Map<String,Object>> props(CompleteRound.Spin spin,double betSize,int level,boolean free){
  List<Map<String,Object>> out=new ArrayList<>();int[] ids=new int[20];for(int p=0;p<20;p++)ids[p]=p+2;int nextId=22;
  for(int c=0;c<spin.cascades().size();c++){
   CompleteRound.Cascade page=spin.cascades().get(c);
   if(c>0){
    int[] predecessors=rules.predecessorPositions(spin.cascades().get(c-1),page),newIds=new int[20];
    for(int p=0;p<20;p++)newIds[p]=predecessors[p]<0?nextId++:ids[predecessors[p]];
    ids=newIds;
   }
   int[] board=page.symbols();boolean[] gold=page.gold(),winning=rules.winningPositions(board);
   List<List<Map<String,Object>>> columns=new ArrayList<>();
   for(int col=0;col<5;col++){
    List<Map<String,Object>> cells=new ArrayList<>();
    for(int row=0;row<4;row++){int p=col*4+row;cells.add(Map.of("id",ids[p],"is_gold",gold[p]?1:0,"is_win",winning[p]?1:0,"prop",board[p]));}
    columns.add(cells);
   }
   int multiple=rules.multiplier(c,free);List<Map<String,Object>> winArray=new ArrayList<>();double amount=0;
   for(GameRuleCore.Win w:rules.evaluate(board)){
    double win=money(betSize*level*w.payoutUnits()*multiple);amount+=win;
    winArray.add(Map.of("multiple",multiple,"odd",w.odds(),"prop",w.symbol(),"reel",w.reels()-1,"way",w.ways(),"win_amout",win));
   }
   out.add(Map.of("props_value",columns,"total_amout",money(amount),"win_array",winArray));
  }
  return out;
 }
 private Map<String,Object> frees(CompleteRound.Spin spin,boolean special,double bet,double betAmount,int level,double cumulative){if(!special)return new LinkedHashMap<>(Map.of("bet",0,"bet_amount",0,"fw",0,"last_round_id_win",0,"level",0,"surplus_times",0,"total_times",0,"total_win_amount",0));Map<String,Object> f=new LinkedHashMap<>();f.put("bet",bet);f.put("bet_amount",betAmount);f.put("fw",0);f.put("last_round_id_win",0);f.put("level",level);f.put("surplus_times",spin.freeRemaining());f.put("total_times",spin.freeTotal());f.put("total_win_amount",cumulative);return f;}
 private List<List<String>> goldArray(boolean[] gold){List<List<String>> out=new ArrayList<>();for(int col=1;col<=3;col++){List<String> row=new ArrayList<>();for(int r=0;r<4;r++)row.add(gold[col*4+r]?"1":"0");out.add(row);}return out;}
 private double spinWin(CompleteRound.Spin spin,double bet,int level,boolean free){double n=0;for(int i=0;i<spin.cascades().size();i++)n+=bet*level*rules.payoutUnits(spin.cascades().get(i).symbols(),i,free);return money(n);}
 static double money(double x){return Math.round(x*100.0)/100.0;}
}
