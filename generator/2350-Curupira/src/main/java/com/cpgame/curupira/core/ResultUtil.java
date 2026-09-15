package com.cpgame.curupira.core;
import com.cpgame.curupira.model.Award;
import com.cpgame.curupira.model.CompleteRound;
import com.cpgame.curupira.model.EvaluatedBoard;
import com.cpgame.curupira.model.RoundAnalysis;
import java.util.ArrayList;
import java.util.List;
/** 独立反推结果；候选生成器只提供符号事实。 */
public final class ResultUtil{
 public EvaluatedBoard evaluate(List<Integer>symbols){
  validate(symbols);int scatters=(int)symbols.stream().filter(v->v==GameRules.SCATTER).count();List<Award>awards=new ArrayList<>();int sum=0;
  for(int line=0;line<GameRules.PAYLINES.length;line++){Award a=line(symbols,GameRules.PAYLINES[line],line+1);if(a!=null){awards.add(a);sum=Math.addExact(sum,a.o());}}
  List<Integer>expanding=new ArrayList<>();for(int c=0;c<GameRules.COLUMNS;c++){int o=c*GameRules.ROWS;if(symbols.get(o)==GameRules.WILD&&symbols.get(o+1)==GameRules.WILD&&symbols.get(o+2)==GameRules.WILD)expanding.add(c);}
  return new EvaluatedBoard(List.copyOf(symbols),scatters,List.copyOf(awards),sum,List.copyOf(expanding));
 }
 public void assertOrdinaryTerminal(EvaluatedBoard b){if(b.scatterCount()>=GameRules.SCATTER_TRIGGER)throw new IllegalArgumentException("Unresolved special mode");}
 public void assertIndependentLoss(EvaluatedBoard b){assertOrdinaryTerminal(b);if(!b.awards().isEmpty()||b.multiplierSum()!=0)throw new IllegalArgumentException("Not LOSS");}
 public RoundAnalysis analyzeRound(CompleteRound round){
  if(round==null||round.roundKey()<=0||round.deliveries().isEmpty())throw new IllegalArgumentException("Complete Round identity missing");
  List<EvaluatedBoard>derived=new ArrayList<>();int multiplier=0;
  for(int i=0;i<round.deliveries().size();i++){
   if(round.deliveries().get(i).deliveryIndex()!=i+1)throw new IllegalArgumentException("Delivery index is not contiguous");
   EvaluatedBoard value=evaluate(round.deliveries().get(i).evaluatedBoard().ps());assertOrdinaryTerminal(value);
   derived.add(value);multiplier=Math.addExact(multiplier,value.multiplierSum());
  }
  return new RoundAnalysis(GameRules.ORDINARY_POOL,multiplier,derived);
 }
 public int redisMultiplier(com.cpgame.curupira.model.CompleteRoundFact fact){
  if(fact==null)throw new IllegalArgumentException("Complete Round fact missing");
  return fact.redisMultiplier();
 }
 private static void validate(List<Integer>s){if(s==null||s.size()!=GameRules.CELL_COUNT)throw new IllegalArgumentException("res.ps must contain 15 symbols");if(s.stream().anyMatch(v->v==null||!GameRules.SYMBOLS.contains(v)))throw new IllegalArgumentException("Unknown symbol");}
 private static Award line(List<Integer>s,int[]rows,int number){int candidate=GameRules.WILD;for(int c=0;c<GameRules.COLUMNS;c++){int v=s.get(wireIndex(c,rows[c]));if(v!=GameRules.WILD){if(v==GameRules.SCATTER)return null;candidate=v;break;}}int count=0;for(int c=0;c<GameRules.COLUMNS;c++){int v=s.get(wireIndex(c,rows[c]));if(v!=candidate&&v!=GameRules.WILD)break;count++;}Integer m=GameRules.PAYOUTS.get(candidate).get(count);return m==null?null:new Award(count,number,m,candidate);}
 /** 前端 payline 行号从上到下为 0..2，而 wire ps 在每轴内按相反方向存放。 */
 private static int wireIndex(int column,int visualRow){return column*GameRules.ROWS+(GameRules.ROWS-1-visualRow);}
}
