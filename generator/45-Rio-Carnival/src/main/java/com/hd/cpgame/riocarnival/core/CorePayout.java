package com.hd.cpgame.riocarnival.core;
import java.math.BigDecimal;
import java.util.*;
/** Production award calculation. ResultUtil separately enumerates all symbol candidates as its oracle. */
final class CorePayout {
    private CorePayout(){}
    static WinEvaluation evaluate(List<String> board,BigDecimal bs,int bl,int rpx) {
        Map<String,Map<String,Integer>> matches=new LinkedHashMap<String,Map<String,Integer>>();
        Map<Integer,BigDecimal> awards=new LinkedHashMap<Integer,BigDecimal>();
        BigDecimal total=BigDecimal.ZERO;
        for(int line=0;line<25;line++) {
            String[] symbols=new String[5];
            for(int reel=0;reel<5;reel++)symbols[reel]=board.get(reel*3+GameRules.PAYLINES[line][reel]);
            int wildPrefix=0;
            while(wildPrefix<5&&GameRules.WILD.equals(symbols[wildPrefix]))wildPrefix++;
            String ordinary=wildPrefix<5?symbols[wildPrefix]:null;
            String winner=null;int count=0;int units=0;
            if(ordinary!=null&&!GameRules.SCATTER.equals(ordinary)) {
                int n=wildPrefix;boolean hasWild=wildPrefix>0;
                while(n<5&&(ordinary.equals(symbols[n])||GameRules.WILD.equals(symbols[n]))) {
                    hasWild|=GameRules.WILD.equals(symbols[n]);n++;
                }
                Integer pay=GameRules.PAYTABLE.get(ordinary).get(n);
                if(pay!=null&&pay>0){units=pay*(hasWild?2:1);winner=ordinary;count=n;}
            }
            Integer pure=GameRules.PAYTABLE.get(GameRules.WILD).get(wildPrefix);
            if(pure!=null&&pure*2>units){units=pure*2;winner=GameRules.WILD;count=wildPrefix;}
            if(units>0) {
                BigDecimal award=bs.multiply(BigDecimal.valueOf((long)bl*units*(rpx==0?1:rpx))).setScale(2,java.math.RoundingMode.HALF_UP).stripTrailingZeros();
                Map<String,Integer> match=new LinkedHashMap<String,Integer>();match.put(winner,count);
                matches.put(Integer.toString(line+1),match);awards.put(line+1,award);total=total.add(award);
            }
        }
        return new WinEvaluation(total.stripTrailingZeros(),matches,awards);
    }
}
