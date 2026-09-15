package com.cpgame.curupira.core;
import java.util.LinkedHashMap;
import java.util.Map;
public record GenerationPolicy(Map<Integer,Integer> symbolWeights,int constructionAttempts,int fallbackSamples,
 int maxRoundMultiplier,int maxSpecialSteps,int maxConsecutiveWins){
 public GenerationPolicy{
  symbolWeights=Map.copyOf(symbolWeights);
  if(!symbolWeights.keySet().equals(GameRules.SYMBOLS))throw new IllegalArgumentException("Weights must cover current symbol IDs");
  if(symbolWeights.values().stream().anyMatch(v->v==null||v<0)||symbolWeights.values().stream().mapToLong(Integer::longValue).sum()<=0)throw new IllegalArgumentException("Invalid weights");
  Map<Integer,Integer> validatedWeights=symbolWeights;
  if(GameRules.NON_SPECIAL_SYMBOLS.stream().filter(id->validatedWeights.get(id)>0).count()<6||constructionAttempts<1||fallbackSamples<1||maxRoundMultiplier<1||maxSpecialSteps<1||maxConsecutiveWins<1)throw new IllegalArgumentException("Invalid generation limits");
 }
 public static GenerationPolicy ordinaryPaidDefaults(){
  Map<Integer,Integer>w=new LinkedHashMap<>();for(int id:GameRules.SYMBOLS.stream().sorted().toList())w.put(id,1);
  return new GenerationPolicy(w,5,10,20000,30,10);
 }
 public GenerationPolicy withOrdinaryLimits(int attempts,int fallback,int maxMultiplier,int consecutiveWins){
  return new GenerationPolicy(symbolWeights,attempts,fallback,maxMultiplier,1,consecutiveWins);
 }
}
