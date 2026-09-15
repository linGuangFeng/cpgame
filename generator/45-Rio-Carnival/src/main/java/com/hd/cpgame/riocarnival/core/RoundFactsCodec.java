package com.hd.cpgame.riocarnival.core;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
/** R45C3|initial-free-count|free-multiplier|15 hex symbol codes per ordered board. */
public final class RoundFactsCodec {
    public String encode(GeneratedRound round) throws IOException {
        RoundResult inferred=RoundVerifier.verify(round);
        StringBuilder out=new StringBuilder("R45C3|").append(inferred.initialFreeSpins).append('|').append(inferred.freeMultiplier).append('|');
        for(SpinStep step:round.steps)for(String symbol:step.rskl)out.append("0123456789ABC".charAt(GameRules.SYMBOLS.indexOf(symbol)));
        return out.toString();
    }
    public GeneratedRound decode(String member)throws IOException{return decode(member,new BigDecimal("0.02"),1);}
    public GeneratedRound decode(String member,BigDecimal bs,int bl)throws IOException {
        try {
            if(member==null||!member.matches("R45C3\\|[0-9]{1,2}\\|[0-9]\\|[0-9A-C]+"))throw new IllegalArgumentException("Invalid R45C3 ASCII member");
            String[] p=member.split("\\|",-1);
            int initial=Integer.parseInt(p[1]),multiplier=Integer.parseInt(p[2]);
            if(p[3].length()%15!=0||p[3].length()>DealingModel.MAX_STEPS*15)throw new IllegalArgumentException("Invalid complete-round board count");
            List<List<String>> boards=new ArrayList<List<String>>();
            for(int offset=0;offset<p[3].length();offset+=15) {
                List<String> board=new ArrayList<String>();
                for(int i=0;i<15;i++)board.add(GameRules.SYMBOLS.get(Character.digit(p[3].charAt(offset+i),16)));
                DealingModel.checkGeneratedBoard(board,offset>0);boards.add(board);
            }
            GeneratedRound round=CompleteRoundFactory.rebuild(bs,bl,initial,multiplier,boards);
            RoundResult result=RoundVerifier.verify(round);
            if(result.retriggerCount>DealingModel.MAX_RETRIGGERS)throw new IllegalArgumentException("Retrigger bound exceeded");
            return round;
        }catch(RuntimeException e){throw new IOException("Invalid Rio Carnival pool member",e);}
    }
}
