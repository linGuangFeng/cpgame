package com.cpgame.sharpshooter.core;
import java.util.*;

/** Independent payout oracle: enumerate eligible all-way paths without calling Core payout logic. */
public final class ResultUtil {
 public enum Outcome {NORMAL_LOSS,NORMAL_WIN,FREE_SPINS}
 public record Analysis(Outcome outcome,int payoutUnits,int integerMultiplier,int spinCount,int cascadeCount){}
 // Original initRoom prop_odds, indexed by symbol and reel span 3/4/5.
 private static final int[][] ORIGINAL_PAY={{2,5,10},{2,5,10},{4,10,20},{4,10,20},{6,15,40},{8,20,60},{10,40,80},{15,60,100}};
 public ResultUtil(GameRuleCore rules){Objects.requireNonNull(rules);}
 public int recomputePage(int[] board,int index,boolean free){
  if(board.length!=20||index<0)throw new IllegalArgumentException("oracle page dimensions");
  int award=0;
  for(int symbol=1;symbol<=8;symbol++){
   for(int span=5;span>=3;span--){
    int paths=paths(board,symbol,0,span);
    if(paths>0){award=Math.addExact(award,Math.multiplyExact(paths,ORIGINAL_PAY[symbol-1][span-3]));break;}
   }
  }
  int multiplier=(free?new int[]{2,4,6,10}:new int[]{1,2,3,5})[Math.min(index,3)];
  return Math.multiplyExact(award,multiplier);
 }
 private int paths(int[] board,int symbol,int column,int span){
  if(column==span)return 1;
  int sum=0;for(int row=0;row<4;row++){int value=board[column*4+row];if(value==symbol||value==10)sum=Math.addExact(sum,paths(board,symbol,column+1,span));}
  return sum;
 }
 public Analysis analyze(CompleteRound round){
  int units=0,pages=0,total=round.spins().get(0).freeTotal();
  if(round.spins().size()!=total+1)throw new IllegalArgumentException("oracle incomplete round");
  for(int i=0;i<round.spins().size();i++){
   CompleteRound.Spin spin=round.spins().get(i);
   if(spin.paid()!=(i==0)||spin.freeRemaining()!=(total==0?0:total-i))throw new IllegalArgumentException("oracle counter discontinuity");
   for(int c=0;c<spin.cascades().size();c++){
    int amount=recomputePage(spin.cascades().get(c).symbols(),c,i>0);
    if((amount==0)!=(c==spin.cascades().size()-1))throw new IllegalArgumentException("oracle cascade boundary");
    units=Math.addExact(units,amount);pages++;
   }
  }
  Outcome outcome=round.spins().size()>1?Outcome.FREE_SPINS:units==0?Outcome.NORMAL_LOSS:Outcome.NORMAL_WIN;
  // Exact integer key in hundredths of total-bet multiplier; never rounded.
  return new Analysis(outcome,units,Math.multiplyExact(units,5),round.spins().size(),pages);
 }
}
