package com.cpgame.fiesta.controller;

import com.cpgame.fiesta.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.security.SecureRandom;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolHistoryTest {
    @Test void screenshotHistorySymbolsLinesAndAmountsMatch() throws Exception {
        int[] board={3,50,50,3,50,50,3,50,50};
        GameRound round=new GameRound(List.of(new RoundState(RoundState.Mode.NORMAL,board,new int[9],new int[0],0,-1,0)));
        SessionState session=new SessionState();session.roundId=123;session.balance=100;
        var data=new ProtocolProjection().project(session,round,0,.08,5);
        assertEquals(41.2,((Number)data.get("tw")).doubleValue(),1e-8);
        assertEquals(20.6,((Number)data.get("o")).doubleValue(),1e-8);
        assertEquals(39.2,((Number)data.get("cg")).doubleValue(),1e-8);
        assertEquals(100.,((Number)data.get("sg")).doubleValue(),1e-8);
        assertEquals(139.2,session.balance,1e-8);
        session.currentDeliveries.add(data);
        var method=ControllerMain.class.getDeclaredMethod("historyItem",SessionState.class);method.setAccessible(true);
        Map<?,?> item=(Map<?,?>)method.invoke(new ControllerMain(),session);
        assertEquals(List.of(data),item.get("res"));
        Map<?,?> result=(Map<?,?>)data.get("res");List<?> wins=(List<?>)result.get("wa");
        assertEquals(3,wins.size());
        assertEquals(3,((Map<?,?>)wins.get(1)).get("s"));assertEquals(2,((Map<?,?>)wins.get(1)).get("l"));
        assertEquals(50,((Map<?,?>)wins.get(2)).get("s"));assertEquals(3,((Map<?,?>)wins.get(2)).get("l"));
    }
    @Test void fiveProviderLinesHighlightTheirOwnSymbol(){
        int[][] lines={{1,4,7},{0,3,6},{2,5,8},{0,4,8},{2,4,6}};
        for(int line=0;line<5;line++){
            int[] board={2,3,5,10,20,2,3,5,10};for(int pos:lines[line])board[pos]=50;
            var round=new GameRound(List.of(new RoundState(RoundState.Mode.NORMAL,board,new int[9],new int[0],0,-1,0)));
            Map<?,?> result=(Map<?,?>)new ProtocolProjection().project(new SessionState(),round,0,.08,1).get("res");
            for(Object entry:(List<?>)result.get("wa")){Map<?,?> w=(Map<?,?>)entry;int number=(Integer)w.get("l"),symbol=(Integer)w.get("s");for(int pos:lines[number-1])assertEquals(symbol,board[pos]);}
        }
    }
    @Test void multiplierStageCounterResetsAfterRespinAndHasSettlement(){
        int[] initial={50,50,50,2,3,5,50,50,50},full=new int[9];Arrays.fill(full,50);
        int[] material=new int[9];material[4]=10;
        GameRound round=new GameRound(List.of(
                new RoundState(RoundState.Mode.RESPIN_UNTIL_WIN,initial,new int[9],new int[0],1,1,50),
                new RoundState(RoundState.Mode.MULTIPLIER_STICKY,full,new int[9],new int[0],1,0,50),
                new RoundState(RoundState.Mode.MULTIPLIER_STICKY,full,material,new int[]{4},1,0,50),
                new RoundState(RoundState.Mode.MULTIPLIER_STICKY,full,material,new int[0],0,0,50)));
        SessionState session=new SessionState();ProtocolProjection projection=new ProtocolProjection();
        for(int i=0;i<4;i++){
            Map<String,Object> data=projection.project(session,round,i,.08,1);Map<?,?> feature=(Map<?,?>)data.get("f");
            if(i>0){assertEquals(i-1,feature.get("cn"));assertEquals(2L,feature.get("ca"));}
            if(i==3){assertEquals(200.,((Number)data.get("tw")).doubleValue(),1e-8);assertEquals(List.of(),feature.get("pcn"));}
        }
        assertEquals(100199.6,session.balance,1e-8);
    }
}
