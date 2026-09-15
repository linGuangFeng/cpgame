package com.cpgame.clubgoddess.core;

import com.cpgame.clubgoddess.core.GameModels.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Independent reverse calculator; it never calls generation-side ResultMath. */
public final class ResultUtil {
    private ResultUtil(){}
    public static ResultAnalysis analyze(GameResult r){
        verifyBoard(r.props().prop());if(r.type()==2&&count(r.props().prop(),9)!=0)fail("Scatter in free entry");List<WinItem>raw=oracleWays(r.props().prop(),r.bet(),r.level());int multiplier=r.type()==2?r.props().m():1;
        List<WinItem>expected=raw.stream().map(w->new WinItem(w.odd(),w.pos_arr(),money(w.tw().multiply(BigDecimal.valueOf(multiplier))),w.ways(),w.wp())).toList();
        BigDecimal total=money(expected.stream().map(WinItem::tw).reduce(BigDecimal.ZERO,BigDecimal::add));
        if(!same(total,r.total_win())||!same(total,r.props().tw())||!sameWins(expected,r.props().win_arr()))fail("win projection mismatch");
        BigDecimal stake=money(r.bet().multiply(BigDecimal.valueOf((long)r.level()*GameRuleDefinition.BASE_BET_FACTOR)));
        if(!same(stake,r.bet_gold()))fail("stake mismatch");BigDecimal change=r.type()==1?total.subtract(stake):total;
        if(!same(change,r.change_gold())||!same(r.start_gold().add(change),r.end_gold()))fail("ledger mismatch");
        BigDecimal odds=total.divide(stake,14,RoundingMode.HALF_UP).stripTrailingZeros();if(r.odds()==null||odds.subtract(r.odds()).abs().compareTo(odds.abs().max(BigDecimal.ONE).multiply(new BigDecimal("0.000000000001")))>0)fail("odds mismatch");
        int scatter=count(r.props().prop(),9);if(scatter!=r.props().frees_prop())fail("scatter mismatch");boolean continuation=r.frees().st()>0;
        ResultMode mode=r.type()==2||r.frees().tt()>0?ResultMode.FREE_SPINS:(total.signum()==0?ResultMode.ORDINARY_PAID_LOSS:ResultMode.ORDINARY_PAID_WIN);
        return new ResultAnalysis(mode,total,total.divide(stake,14,RoundingMode.HALF_UP).stripTrailingZeros(),!continuation,continuation,scatter,expected);
    }
    public static int integerMultiplier(RoundBundle round){return round.analysis().totalWin().divide(round.deliveries().get(0).result().bet(),0,RoundingMode.UNNECESSARY).intValueExact();}
    public static void verifyBaseResult(GameResult r){analyze(r);}public static boolean isIndependentOrdinaryLoss(GameResult r){try{return analyze(r).mode()==ResultMode.ORDINARY_PAID_LOSS;}catch(Exception e){return false;}}
    private static List<WinItem>oracleWays(List<Integer>b,BigDecimal bet,int level){LinkedHashSet<Integer>symbols=new LinkedHashSet<>();for(int row=0;row<3;row++)if(b.get(row)>=1&&b.get(row)<=8)symbols.add(b.get(row));List<WinItem>out=new ArrayList<>();for(int symbol:symbols){int reels=0,ways=1;List<Integer>pos=new ArrayList<>();for(int reel=0;reel<5;reel++){List<Integer>rp=new ArrayList<>();for(int row=0;row<3;row++){int p=reel*3+row,v=b.get(p);if(v==symbol||(v==10&&reel>=1&&reel<=3))rp.add(p);}if(rp.isEmpty())break;reels++;ways*=rp.size();pos.addAll(rp);}if(reels>=3){int odd=GameRuleDefinition.odd(symbol,reels);BigDecimal tw=money(bet.multiply(BigDecimal.valueOf(level)).multiply(BigDecimal.valueOf(odd)).multiply(BigDecimal.valueOf(ways)));out.add(new WinItem(odd,List.copyOf(pos),tw,ways,symbol));}}return List.copyOf(out);}
    private static void verifyBoard(List<Integer>b){if(b==null||b.size()!=15)fail("board size");if(count(b,10)>2)fail("Wild board maximum 2");for(int c=0;c<5;c++)if(count(b.subList(c*3,c*3+3),9)>1)fail("Scatter reel maximum 1");for(int p=0;p<15;p++){int v=b.get(p);if(v<1||v>10||(v==10&&(p/3==0||p/3==4)))fail("board symbol");}}
    private static boolean sameWins(List<WinItem>a,List<WinItem>b){
        if(b==null||a.size()!=b.size())return false;
        Map<Integer,WinItem>bySymbol=new HashMap<>();for(WinItem y:b)if(bySymbol.put(y.wp(),y)!=null)return false;for(WinItem x:a){WinItem y=bySymbol.get(x.wp());if(y==null||x.odd()!=y.odd()||x.ways()!=y.ways()||!x.pos_arr().equals(y.pos_arr())||!same(x.tw(),y.tw()))return false;}
        return true;
    }
    private static int count(List<Integer>b,int s){int n=0;for(int v:b)if(v==s)n++;return n;}private static BigDecimal money(BigDecimal v){return v.setScale(6,RoundingMode.HALF_UP).stripTrailingZeros();}private static boolean same(BigDecimal a,BigDecimal b){return a!=null&&b!=null&&a.compareTo(b)==0;}private static void fail(String s){throw new IllegalArgumentException(s);}
}
