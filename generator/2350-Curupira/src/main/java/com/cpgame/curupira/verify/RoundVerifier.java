package com.cpgame.curupira.verify;
import com.cpgame.curupira.core.*;
import com.cpgame.curupira.model.CompleteRound;
import com.cpgame.curupira.model.CompleteRoundFact;
import com.cpgame.curupira.model.EvaluatedBoard;
import com.cpgame.curupira.model.FeatureStep;
import com.cpgame.curupira.model.RoundAnalysis;
/** 从最小符号事实独立反推每个 Delivery，并复核完整 Round 边界。 */
public final class RoundVerifier{
 private final ResultUtil independent=new ResultUtil();private final GenerationPolicy policy;
 public RoundVerifier(GenerationPolicy p){policy=p;}
 public void verify(CompleteRound r){
  if(r.roundKey()<=0||r.sourceGameId()!=RulesContract.GAME_ID||!r.rulesVersion().equals(RulesContract.RULES_VERSION)||!r.rulesHash().equals(RulesContract.RULES_HASH)||!r.resultPool().equals(RoundFactory.ORDINARY_POOL)||r.deliveries().size()!=1)throw new IllegalArgumentException("Invalid complete boundary");
  RoundAnalysis analysis=independent.analyzeRound(r);
  for(int i=0;i<r.deliveries().size();i++)if(!analysis.deliveries().get(i).equals(r.deliveries().get(i).evaluatedBoard()))throw new IllegalArgumentException("Derived fields mismatch");
  if(analysis.actualMultiplier()>policy.maxRoundMultiplier())throw new IllegalArgumentException("Round multiplier limit");
 }
 public void verifyFact(CompleteRoundFact fact){
  if(fact==null||fact.steps().isEmpty())throw new IllegalArgumentException("Invalid complete fact");
  if(fact.redisMultiplier()>policy.maxRoundMultiplier())throw new IllegalArgumentException("Round multiplier limit");
  if(fact.kind()==CompleteRoundFact.Kind.FREE_EW||fact.kind()==CompleteRoundFact.Kind.BUY_FE){
   for(FeatureStep step:fact.steps()){
    EvaluatedBoard board=independent.evaluate(step.cells());
    if(board.expandingWildColumns().size()!=1)throw new IllegalArgumentException("Free Expanding Wild needs one Wild column");
    if(board.scatterCount()>=GameRules.SCATTER_TRIGGER)throw new IllegalArgumentException("Free step must not retrigger");
   }
  }
  if(fact.kind()==CompleteRoundFact.Kind.TRIGGER){
   EvaluatedBoard board=independent.evaluate(fact.steps().get(0).cells());
   if(board.scatterCount()!=GameRules.SCATTER_TRIGGER)throw new IllegalArgumentException("Trigger needs 3 Scatter");
   if(!board.awards().isEmpty()||board.multiplierSum()!=0)throw new IllegalArgumentException("Trigger must be a non-winning board");
   if(!board.expandingWildColumns().isEmpty())throw new IllegalArgumentException("Trigger must not expand Wild");
  }
  if(fact.kind()==CompleteRoundFact.Kind.HOLD||fact.kind()==CompleteRoundFact.Kind.BUY_HS){
   if(fact.steps().get(fact.steps().size()-1).st()!=0)throw new IllegalArgumentException("Hold must end at st=0");
  }
 }
}
