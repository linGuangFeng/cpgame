package com.cpgame.batchc.cybergo;
import java.math.*;
import java.util.*;
import static com.cpgame.batchc.cybergo.CyberGoRules.*;
import com.cpgame.batchc.cybergo.CyberGoModels.WinMatch;
/** Production ways evaluator. ResultUtil separately scans and verifies all generated steps. */
final class RuleEvaluator {
 static ResultUtil.Evaluation evaluate(List<String> board,int level,BigDecimal size) {
  if(board.size()!=15) throw new IllegalArgumentException("Expected 15 cells");
  Map<String,List<List<Integer>>> cells=new LinkedHashMap<>();
  for(String symbol:PAYING_SYMBOLS) { List<List<Integer>> reels=new ArrayList<>(); for(int r=0;r<5;r++) reels.add(new ArrayList<>()); cells.put(symbol,reels); }
  int sc=0, wild=0;
  for(int i=0;i<15;i++) {
   String s=board.get(i); if(s.equals(SCATTER)) { sc++; continue; }
   if(s.equals(WILD)) wild++;
   for(String target:PAYING_SYMBOLS) if(s.equals(target)||s.equals(WILD)) cells.get(target).get(i/3).add(i/3*10+i%3);
  }
  BigDecimal total=BigDecimal.ZERO.setScale(2); List<WinMatch> matches=new ArrayList<>();List<String> symbols=new ArrayList<>();
  for(String symbol:PAYING_SYMBOLS) {
   var reels=cells.get(symbol); int length=0, ways=1;
   while(length<5&&!reels.get(length).isEmpty()) { ways*=reels.get(length).size();length++; }
   if(length<3) continue;
   BigDecimal win=size.multiply(BigDecimal.valueOf((long)level*ways*PAYTABLE.get(symbol).get(length))).setScale(2,RoundingMode.HALF_UP);
   matches.add(new WinMatch(symbol,win.toPlainString(),reels.subList(0,length).stream().map(List::copyOf).toList()));symbols.add(symbol);total=total.add(win);
  }
  return new ResultUtil.Evaluation(total,List.copyOf(matches),List.copyOf(symbols),sc,wild);
 }
}
