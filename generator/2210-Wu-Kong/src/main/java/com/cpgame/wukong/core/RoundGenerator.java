package com.cpgame.wukong.core;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** 从证据支持的完整联合状态抽样；不读取抓包、fixture 或历史响应。 */
public final class RoundGenerator {
    private final SecureRandom random; private final GameRuleCore rules=new GameRuleCore();
    private final ZeroLossSupport<CompleteRound> losses; private final WeightedPairs lossPairs;
    private final WeightedModes modes; private final EnumMap<CompleteRound.Mode,WeightedPairs> initial=new EnumMap<>(CompleteRound.Mode.class); private final WeightedPairs redeal;
    public RoundGenerator(SecureRandom random,Properties p){this.random=random;Set<String> initialSupport=Set.of("null","0","1","5","10");Set<String> redealSupport=Set.of("0","1","5","10");modes=WeightedModes.parse(req(p,"weights.mode"));initial.put(CompleteRound.Mode.NONE,WeightedPairs.parse(req(p,"weights.initial.none"),initialSupport));initial.put(CompleteRound.Mode.X2,WeightedPairs.parse(req(p,"weights.initial.x2"),initialSupport));initial.put(CompleteRound.Mode.X5,WeightedPairs.parse(req(p,"weights.initial.x5"),initialSupport));initial.put(CompleteRound.Mode.RESPIN,WeightedPairs.parse(req(p,"weights.initial.respin"),initialSupport));redeal=WeightedPairs.parse(req(p,"weights.respin-redeal"),redealSupport);List<WeightedPair> safe=initial.get(CompleteRound.Mode.NONE).values.stream().filter(v->ResultUtil.concatenate(v.pair())==0).toList();if(safe.isEmpty())throw new IllegalArgumentException("no zero pair support");lossPairs=new WeightedPairs(safe);losses=new ZeroLossSupport<>(this::lossCandidate,r->rules.classify(r)==CompleteRound.Outcome.ORDINARY_LOSS,r->r);}
    public CompleteRound generate(){CompleteRound.Mode mode=modes.pick(random);CompleteRound round=new CompleteRound(mode,initial.get(mode).pick(random),mode==CompleteRound.Mode.RESPIN?redeal.pick(random):null);rules.validate(round);return round;}
    private static String req(Properties p,String key){String v=p.getProperty(key);if(v==null||v.isBlank())throw new IllegalArgumentException("missing "+key);return v.trim();}
    private static Set<String> positiveSymbolSupport(Properties p,String prefix,List<String> names){Map<String,String> token=Map.of("blank","null","zero","0","one","1","five","5","ten","10");java.util.HashSet<String> support=new java.util.HashSet<>();for(String name:names){int weight=Integer.parseInt(req(p,prefix+name));if(weight<=0)throw new IllegalArgumentException("symbol weight must be positive: "+prefix+name);support.add(token.get(name));}return Set.copyOf(support);}
    private record WeightedPair(CompleteRound.ReelPair pair,int weight){}
    private record WeightedMode(CompleteRound.Mode mode,int weight){}
    private static final class WeightedPairs{private final List<WeightedPair> values;private WeightedPairs(List<WeightedPair> v){values=List.copyOf(v);}static WeightedPairs parse(String raw,Set<String> support){List<WeightedPair> out=new ArrayList<>();for(String item:raw.split("\\|")){String[] x=item.split(":",2),pair=x[0].split(",",-1);int w=Integer.parseInt(x[1]);if(pair.length!=2||w<=0||!support.contains(pair[0])||!support.contains(pair[1]))throw new IllegalArgumentException("unsupported or invalid joint weight "+item);out.add(new WeightedPair(new CompleteRound.ReelPair(pair[0],pair[1]),w));}return new WeightedPairs(out);}CompleteRound.ReelPair pick(SecureRandom r){int n=values.stream().mapToInt(WeightedPair::weight).sum(),x=r.nextInt(n);for(WeightedPair v:values){x-=v.weight();if(x<0)return v.pair();}throw new IllegalStateException();}}
    private static final class WeightedModes{private final List<WeightedMode> values;private WeightedModes(List<WeightedMode> v){values=List.copyOf(v);}static WeightedModes parse(String raw){List<WeightedMode> out=new ArrayList<>();for(String item:raw.split("\\|")){String[] x=item.split(":",2);int w=Integer.parseInt(x[1]);if(w<=0)throw new IllegalArgumentException("bad mode weight");out.add(new WeightedMode(CompleteRound.Mode.valueOf(x[0]),w));}return new WeightedModes(out);}CompleteRound.Mode pick(SecureRandom r){int n=values.stream().mapToInt(WeightedMode::weight).sum(),x=r.nextInt(n);for(WeightedMode v:values){x-=v.weight();if(x<0)return v.mode();}throw new IllegalStateException();}}

    public CompleteRound lossCandidate(){return new CompleteRound(CompleteRound.Mode.NONE,lossPairs.pick(random),null);}
    public CompleteRound generateLoss(){return losses.generate(this::lossCandidate,random::nextInt);}
}
