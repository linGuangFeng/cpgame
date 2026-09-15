package com.cpgame.curupira.random;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
public final class WeightedSymbolSampler{
 private final RandomSource random;private final List<Integer>ids;private final Map<Integer,Integer>weights;
 public WeightedSymbolSampler(RandomSource random,Map<Integer,Integer>weights){this.random=random;this.weights=Map.copyOf(weights);ids=weights.keySet().stream().sorted().toList();}
 public int next(){return nextFrom(ids);}
 public int nextFrom(List<Integer>allowed){int total=allowed.stream().mapToInt(id->weights.getOrDefault(id,0)).sum();if(total<=0)throw new IllegalArgumentException("Zero allowed weight");int draw=random.nextInt(total);for(int id:allowed){draw-=weights.getOrDefault(id,0);if(draw<0)return id;}throw new IllegalStateException();}
 public List<Integer>distinctFrom(List<Integer>allowed,int count){List<Integer>remaining=new ArrayList<>(allowed),selected=new ArrayList<>(count);while(selected.size()<count){int id=nextFrom(remaining);selected.add(id);remaining.remove(Integer.valueOf(id));}return selected;}
}
