package com.cpgame.g2110.core;
public final class ResultUtil{
 private final GameRuleCore rules;public ResultUtil(GameRuleCore rules){this.rules=rules;}
 /** Recalculate separately from GameRuleCore.evaluate/payoutUnits using its rule data. */
 public long independentPayoutUnits(int[] board){
  if(board==null||board.length!=GameRuleCore.CELLS)throw new IllegalArgumentException("board must be 5x3");
  for(int symbol:board)if(symbol<1||symbol>9)throw new IllegalArgumentException("symbol");
  long units=0;
  for(int line=1;line<=GameRuleCore.PAYLINES;line++){
   int[] positions=rules.linePositions(line);
   // Enumerate each payable symbol, rather than infer the target while scanning a line.
   for(int candidate=1;candidate<=7;candidate++){
    int count=0;boolean containsCandidate=false;
    for(int position:positions){
     int symbol=board[position];
     if(symbol!=candidate&&symbol!=GameRuleCore.WILD)break;
     containsCandidate|=symbol==candidate;count++;
    }
    if(count>=3&&containsCandidate)units=Math.addExact(units,rules.pay(candidate,count));
   }
  }
  return units;
 }
 public long totalUnits(GameRuleCore.CompleteRound round){return round.steps().stream().mapToLong(s->independentPayoutUnits(s.board())).sum();}
 /** Exact integer multiplier of bet-size × level; divide by 20 for total-stake odds. */
 public int integerMultiplier(GameRuleCore.CompleteRound round){return Math.toIntExact(totalUnits(round));}
 public String outcome(GameRuleCore.CompleteRound round){return totalUnits(round)>0?"WIN":"LOSS";}
 public double totalBet(double bet,int level){return money(bet*level*20);}
 public double moneyFromUnits(long units,double bet,int level){return money(units*bet*level);}
 public double money(double value){return Math.round(value*100.0)/100.0;}
}
