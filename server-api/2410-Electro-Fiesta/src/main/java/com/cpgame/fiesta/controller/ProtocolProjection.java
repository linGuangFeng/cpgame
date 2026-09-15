package com.cpgame.fiesta.controller;

import com.cpgame.fiesta.*;
import java.util.*;

final class ProtocolProjection {
    private final GameRuleCore rules=new GameRuleCore(); private final ResultUtil util=new ResultUtil(rules);
    Map<String,Object> project(SessionState session,GameRound round,int index,double betSize,int level){
        RoundState state=round.states().get(index);boolean initial=index==0,terminal=index==round.states().size()-1;ResultUtil.Analysis analysis=util.analyze(round);double totalBet=betSize*level*5;double pageBase=rules.payoutUnits(state.board())*betSize*level;double finalWin=analysis.payoutUnits()*betSize*level;
        if(initial)session.balance-=totalBet;if(terminal)session.balance+=finalWin;
        Map<String,Object> data=new LinkedHashMap<>();data.put("b",betSize);data.put("bg",initial?totalBet:0);data.put("cg",initial?-totalBet:(terminal?finalWin:0));data.put("cl",0);data.put("eg",money(session.balance));data.put("f",feature(state,round,index,betSize,level,terminal?finalWin:0));data.put("l",level);data.put("o",terminal?analysis.payoutUnits():0);data.put("oid",session.roundId);data.put("res",result(state,pageBase,terminal&&state.mode()==RoundState.Mode.MULTIPLIER_STICKY?finalWin:pageBase));data.put("rid",session.roundId);data.put("sg",money(session.balance-(initial?-totalBet:(terminal?finalWin:0))));data.put("small_game_type",state.mode()==RoundState.Mode.NORMAL?0:2);data.put("start_gold",data.get("sg"));data.put("t",initial?1:2);data.put("tw",terminal?money(finalWin):0);data.put("u",24100001);return data;
    }
    private Object feature(RoundState s,GameRound round,int index,double bet,int level,double finalWin){if(s.mode()==RoundState.Mode.NORMAL)return List.of();Map<String,Object> f=new LinkedHashMap<>();f.put("bet",bet);f.put("ca",s.mode()==RoundState.Mode.MULTIPLIER_STICKY?10:0);f.put("cf",s.remaining());f.put("cn",index);f.put("l",level);f.put("p",ints(s.board()));Map<String,Integer> pc=new LinkedHashMap<>();if(s.mode()==RoundState.Mode.MULTIPLIER_STICKY)for(int i=0;i<9;i++)pc.put(String.valueOf(i),s.multipliers()[i]);f.put("pc",s.mode()==RoundState.Mode.MULTIPLIER_STICKY?pc:List.of());f.put("pcn",ints(s.addedPositions()));f.put("pcp",ints(s.multipliers()));f.put("pr",s.respinColumn()+1);f.put("ps",s.baseSymbol());f.put("psp",0);f.put("t",s.mode()==RoundState.Mode.RESPIN_UNTIL_WIN?1:2);f.put("tw",finalWin);return f;}
    private Map<String,Object> result(RoundState s,double base,double display){Map<String,Object> r=new LinkedHashMap<>();r.put("ds",0);r.put("ps",ints(s.board()));r.put("tws",money(display));List<Map<String,Object>> wins=new ArrayList<>();for(GameRuleCore.Win w:rules.evaluateBoard(s.board()))wins.add(new LinkedHashMap<>(Map.of("c",w.count(),"l",w.line(),"o",w.odds(),"s",w.symbol())));r.put("wa",wins);return r;}
    private List<Integer> ints(int[] x){return Arrays.stream(x).boxed().toList();} private double money(double x){return Math.round(x*100.0)/100.0;}
}

