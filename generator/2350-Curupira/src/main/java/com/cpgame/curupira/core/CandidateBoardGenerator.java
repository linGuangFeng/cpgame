package com.cpgame.curupira.core;
import com.cpgame.curupira.random.WeightedSymbolSampler;
import java.util.ArrayList;
import java.util.List;
/** 随机候选牌面生成不包含奖金或模式推断。 */
public final class CandidateBoardGenerator{
 private final WeightedSymbolSampler symbols;
 public CandidateBoardGenerator(WeightedSymbolSampler symbols){this.symbols=symbols;}
 public List<Integer>nextBoard(){
  List<Integer>b=new ArrayList<>(GameRules.CELL_COUNT);
  for(int i=0;i<GameRules.CELL_COUNT;i++)b.add(i<GameRules.ROWS?symbols.nextFrom(GameRules.FIRST_REEL_SYMBOLS):symbols.next());
  return GameRules.atMostOneScatterPerColumn(b);
 }
}
