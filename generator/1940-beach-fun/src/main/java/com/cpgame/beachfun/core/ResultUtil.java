package com.cpgame.beachfun.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Independent verifier. It never calls GameRuleCore.evaluate(), validate(), transition() or payoutUnits(). */
public final class ResultUtil {
    private static final int[][] ORIGINAL_ODDS={{2,5,10},{2,5,10},{4,10,20},{4,10,20},{6,15,40},{8,20,60},{10,40,80},{15,60,100}};
    public static double totalBet(double betGold,int level){return decimal(betGold).multiply(BigDecimal.valueOf(level*20L)).setScale(2,RoundingMode.HALF_UP).doubleValue();}
    public static double moneyFromUnits(long units,double betGold,int level){return decimal(betGold).multiply(BigDecimal.valueOf(level)).multiply(BigDecimal.valueOf(units)).setScale(2,RoundingMode.HALF_UP).doubleValue();}
    public static double money(double v){return decimal(v).setScale(2,RoundingMode.HALF_UP).doubleValue();}
    private static BigDecimal decimal(double v){if(!Double.isFinite(v))throw new IllegalArgumentException("non-finite money");return BigDecimal.valueOf(v);}
    public static int integerMultiplier(GameRuleCore.CompleteRound r){
        if(r.totalUnits()%20!=0)throw new IllegalArgumentException("Round is not an exact integer payout multiplier");
        return Math.toIntExact(r.totalUnits()/20);
    }
    public static String outcome(GameRuleCore.CompleteRound r){return r.win()?"WIN":"LOSS";}
    public static void verify(GameRuleCore.CompleteRound r){
        check(r!=null&&!r.deliveries().isEmpty(),"empty Round");
        long total=0;int remaining=0;boolean free=false,gold=false,cascade=false;
        for(int di=0;di<r.deliveries().size();di++){
            var d=r.deliveries().get(di);
            check(d.free()==(di>0),"free flag");check(di==0||remaining>0,"unsolicited free delivery");
            check(!d.cascades().isEmpty(),"empty delivery");
            int[] previous=null;boolean[] previousGold=null,previousHit=null;
            for(int ci=0;ci<d.cascades().size();ci++){
                var c=d.cascades().get(ci);int[] board=c.board();boolean[] g=c.gold(),hit=new boolean[20];
                check(board.length==20&&g.length==20,"shape");
                for(int p=0;p<20;p++){check(board[p]>=1&&board[p]<=10,"symbol");if(g[p])check(p/4>=1&&p/4<=3&&board[p]<9,"gold position");gold|=g[p];}
                Map<Integer,GameRuleCore.Win> actual=new HashMap<>();
                for(var w:c.wins())check(actual.put(w.symbol(),w)==null,"duplicate win");
                int expectedWins=0;int factor=(d.free()?new int[]{2,4,6,10}:new int[]{1,2,3,5})[Math.min(ci,3)];
                for(int s=1;s<=8;s++){
                    int n=0,ways=1;List<Integer> positions=new ArrayList<>();
                    while(n<5){
                        int cnt=0;
                        for(int row=0;row<4;row++){int pos=n*4+row;if(board[pos]==s||board[pos]==10){cnt++;positions.add(pos);}}
                        if(cnt==0)break;ways*=cnt;n++;
                    }
                    if(n>=3){
                        expectedWins++;var w=actual.get(s);int odd=ORIGINAL_ODDS[s-1][n-3];
                        check(w!=null&&w.reels()==n&&w.ways()==ways&&w.odds()==odd&&w.multiplier()==factor&&w.positions().equals(positions),"independent ways mismatch");
                        total=Math.addExact(total,(long)ways*odd*factor);for(int p:positions)hit[p]=true;
                    }
                }
                check(actual.size()==expectedWins,"unexpected paying symbol");
                check((ci==d.cascades().size()-1)==(expectedWins==0),"delivery terminal");
                if(previous!=null){
                    for(int reel=0;reel<5;reel++){
                        int targetRow=0;
                        for(int row=0;row<4;row++){
                            int p=reel*4+row;if(previousHit[p]&&!previousGold[p])continue;
                            int target=reel*4+targetRow++;
                            check(board[target]==(previousHit[p]?10:previous[p]),"survivor symbol/order");
                            check(g[target]==(!previousHit[p]&&previousGold[p]),"survivor gold");
                        }
                        for(;targetRow<4;targetRow++){int p=reel*4+targetRow;check(board[p]!=10&&!g[p],"illegal refill Wild/gold");}
                    }
                }
                previous=board;previousGold=g;previousHit=hit;
            }
            int scatter=0;for(int s:previous)if(s==9)scatter++;
            int award=scatter<3?0:12+(scatter-3)*2;
            remaining=(di==0?0:remaining-1)+award;
            check(d.awardedFreeSpins()==award&&d.remainingFreeSpins()==remaining,"free award/count");
            free|=d.free()||award>0;cascade|=d.cascades().size()>1;
        }
        check(remaining==0,"unfinished Round");
        check(total==r.totalUnits()&&r.win()==(total>0)&&r.freeFeature()==free&&r.cascadeFeature()==cascade&&r.goldFeature()==gold,"Round totals/features");
    }
    private static void check(boolean ok,String message){if(!ok)throw new IllegalArgumentException(message);}
}
