package com.cpgame.curupira.core;
import com.cpgame.curupira.model.EvaluatedBoard;
import com.cpgame.curupira.random.WeightedSymbolSampler;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
/** 固定线 LOSS 构造：前两列使用互不相交的符号，使所有从左到右的中奖线终止。 */
public final class ConstructiveLossGenerator{
 private final WeightedSymbolSampler symbols;private final CandidateBoardGenerator candidates;private final ResultUtil util;private final GenerationPolicy policy;private final List<EvaluatedBoard> defaults;private final java.security.SecureRandom fallbackRandom=new java.security.SecureRandom();
 public ConstructiveLossGenerator(WeightedSymbolSampler s,CandidateBoardGenerator c,ResultUtil u,GenerationPolicy p){symbols=s;candidates=c;util=u;policy=p;
  List<EvaluatedBoard> pool=new ArrayList<>(10);for(int i=0;i<10;i++)pool.add(tryConstructOnce().orElseThrow(()->new IllegalStateException("invalid loss default")));defaults=List.copyOf(pool);
 }
 public EvaluatedBoard nextLoss(){return generateWithCandidates(this::tryConstructOnce);}
 EvaluatedBoard generateWithCandidates(java.util.function.Supplier<Optional<EvaluatedBoard>> proposals){
  for(int attempt=0;attempt<5;attempt++){Optional<EvaluatedBoard> result=proposals.get();if(result.isPresent())return result.get();}
  return defaults.get(fallbackRandom.nextInt(10));
 }
 public Optional<EvaluatedBoard>tryConstructOnce(){List<Integer>first=symbols.distinctFrom(GameRules.NON_SPECIAL_SYMBOLS,6),board=new ArrayList<>(GameRules.CELL_COUNT);board.addAll(first);while(board.size()<GameRules.CELL_COUNT)board.add(symbols.next());board=new ArrayList<>(GameRules.atMostOneScatterPerColumn(board));int scatter=0;for(int i=0;i<board.size();i++)if(board.get(i)==GameRules.SCATTER&&++scatter>=GameRules.SCATTER_TRIGGER)board.set(i,symbols.nextFrom(GameRules.NON_SPECIAL_SYMBOLS));EvaluatedBoard e=util.evaluate(board);try{util.assertIndependentLoss(e);return Optional.of(e);}catch(IllegalArgumentException ignored){return Optional.empty();}}
}
