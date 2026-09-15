package com.cpgame.sharpshooter.loader;
import com.cpgame.sharpshooter.core.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Raw-award oracle regression and explicit conditional entry/joint distribution comparison. */
public final class GenerationModelValidation {
 private static final ObjectMapper JSON=new ObjectMapper();
 private static final GameRuleCore CORE=new GameRuleCore();
 private static final ResultUtil ORACLE=new ResultUtil(CORE);
 private static final Map<String,Stats> trainingStats=new TreeMap<>(),generatedStats=new TreeMap<>();
 private static final Map<String,TreeMap<Integer,Integer>> trainingJoint=new TreeMap<>(),generatedJoint=new TreeMap<>();
 private static int originalStates,originalPages;
 public static void main(String[] args)throws Exception{
  if(args.length!=3)throw new IllegalArgumentException("training-corpus.json holdout-corpus.json report.json");
  JsonNode training=JSON.readTree(Path.of(args[0]).toFile()),holdout=JSON.readTree(Path.of(args[1]).toFile());
  if(holdout.size()<100)throw new IllegalStateException("fewer than 100 reserved complete rounds");
  Set<Integer> starts=new HashSet<>();
  for(JsonNode r:training){starts.add(r.path("start").asInt());CompleteRound round=verifyOriginal(r);accumulate(round,trainingStats,trainingJoint);}
  for(JsonNode r:holdout){if(!starts.add(r.path("start").asInt()))throw new IllegalStateException("holdout overlap");verifyOriginal(r);}
  System.out.println("Original oracle PASS rounds="+(training.size()+holdout.size())+" states="+originalStates+" pages="+originalPages);
  RoundGenerator generator=new RoundGenerator(new SecureRandom(),CORE);RoundCodec codec=new RoundCodec();
  Set<String> hashes=new HashSet<>();Map<String,Integer> outcomes=new TreeMap<>();int maxSpins=0,maxPages=0;
  for(int i=0;i<10000;i++){
   CompleteRound round=i<4500?generator.ordinary(false):i<9000?generator.ordinary(true):generator.freeSpins();
   CORE.validateRound(round);ResultUtil.Analysis a=ORACLE.analyze(round);
   for(int s=0;s<round.spins().size();s++)for(int p=0;p<round.spins().get(s).cascades().size();p++){
    int[] b=round.spins().get(s).cascades().get(p).symbols();
    if(ORACLE.recomputePage(b,p,s>0)!=CORE.payoutUnits(b,p,s>0))throw new IllegalStateException("independent generated award disagreement");
   }
   String member=codec.encode(round);
   if(!member.equals(codec.encode(codec.decode(member)))||!member.chars().allMatch(c->c>=32&&c<=126))throw new IllegalStateException("ASCII codec roundtrip");
   hashes.add(sha(member));outcomes.merge(a.outcome().name(),1,Integer::sum);
   maxSpins=Math.max(maxSpins,a.spinCount());maxPages=Math.max(maxPages,a.cascadeCount());
   accumulate(round,generatedStats,generatedJoint);
   if((i+1)%1000==0)System.out.println("Generated and independently verified "+(i+1));
  }
  List<Map<String,Object>> comparisons=new ArrayList<>();boolean distributions=true;
  for(String key:trainingStats.keySet()){
   Stats a=trainingStats.get(key),b=generatedStats.get(key);
   if(b==null){comparisons.add(Map.of("entry",key,"pass",false,"reason","no generated observations"));distributions=false;continue;}
   double distance=0;for(int symbol=1;symbol<=10;symbol++)distance+=Math.abs(ratio(a.symbols[symbol],a.cells)-ratio(b.symbols[symbol],b.cells));distance/=2;
   double tolerance=.12+Math.sqrt(Math.log(200)/(2.0*Math.max(1,a.pages)));
   boolean pass=distance<=tolerance;distributions&=pass;
   comparisons.add(Map.of("entry",key,"training",a.report(),"generated",b.report(),"symbolTotalVariation",distance,"tolerance",tolerance,"pass",pass));
  }
  List<Map<String,Object>> joints=new ArrayList<>();
  for(String key:trainingJoint.keySet()){
   var a=trainingJoint.get(key);var b=generatedJoint.get(key);
   if(b==null){distributions=false;joints.add(Map.of("metric",key,"pass",false));continue;}
   double ks=ks(a,b);int denominator=a.values().stream().mapToInt(Integer::intValue).sum();
   double tolerance=(key.startsWith("payout:")?.20:.15)+Math.sqrt(Math.log(200)/(2.0*denominator));
   boolean pass=ks<=tolerance;distributions&=pass;
   joints.add(Map.of("metric",key,"trainingCounts",a,"generatedCounts",b,"cdfDistance",ks,"tolerance",tolerance,"pass",pass));
  }
  Map<String,Object> out=new LinkedHashMap<>();
  out.put("schemaVersion",2);out.put("gameId",1090);out.put("rulesHash",GameRuleCore.RULES_HASH);
  out.put("status",distributions?"PASS":"FAIL_DISTRIBUTION");
  out.put("trainingCompleteRounds",training.size());out.put("holdoutCompleteRounds",holdout.size());out.put("holdoutDisjoint",true);
  out.put("originalCompleteRoundsValidated",training.size()+holdout.size());out.put("originalStatesValidated",originalStates);out.put("originalPagesValidated",originalPages);
  out.put("expectedAwardSource","unaltered raw-response total_amout / (bet * level), retained in source-hashed regression corpora");
  out.put("oracle","independent all-way path enumeration; no Core payout call");
  out.put("generatedCompleteRounds",10000);out.put("generatedUniqueMembers",hashes.size());out.put("outcomes",outcomes);out.put("maxSpins",maxSpins);out.put("maxRoundPages",maxPages);
  out.put("entryComparisons",comparisons);out.put("jointComparisons",joints);
  out.put("acceptanceTolerances","Predeclared approximation margins: categorical symbol TV .12, page-count CDF .15, payout-bin CDF .20; plus sqrt(log(200)/(2*n)) allowance for finite evidence. Not a proof of identical provider probabilities.");
  out.put("coreChecks",List.of("original award oracle","complete paid/free counters","fresh-entry bounds","retained symbol order","gold-to-Wild","terminal pages","generated independent awards","ASCII member roundtrip"));
  JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[2]).toFile(),out);
  System.out.println(out.get("status")+" report="+args[2]);
  if(!distributions)throw new IllegalStateException("generation distribution comparison failed");
 }
 private static CompleteRound verifyOriginal(JsonNode root){
  List<CompleteRound.Spin> spins=new ArrayList<>();
  for(JsonNode state:root.path("states")){
   List<CompleteRound.Cascade> pages=new ArrayList<>();int index=0;
   for(JsonNode page:state.path("cascades")){
    int[] symbols=JSON.convertValue(page.path("symbols"),int[].class);boolean[] gold=JSON.convertValue(page.path("gold"),boolean[].class);
    CompleteRound.Cascade current=new CompleteRound.Cascade(symbols,gold);pages.add(current);
    int expected=page.path("expectedUnits").asInt();
    if(ORACLE.recomputePage(symbols,index,!state.path("paid").asBoolean())!=expected||CORE.payoutUnits(symbols,index,!state.path("paid").asBoolean())!=expected)
     throw new IllegalStateException("original award mismatch "+state.path("path").asText()+" page="+index);
    if(index>0){
     int[] previousIds=JSON.convertValue(state.path("cascades").get(index-1).path("ids"),int[].class),ids=JSON.convertValue(page.path("ids"),int[].class);
     int[] sources=CORE.predecessorPositions(pages.get(index-1),current);
     for(int pos=0;pos<20;pos++)if(sources[pos]>=0&&ids[pos]!=previousIds[sources[pos]])throw new IllegalStateException("original retained ID mismatch");
    }
    index++;originalPages++;
   }
   spins.add(new CompleteRound.Spin(state.path("paid").asBoolean(),state.path("freeTotal").asInt(),state.path("freeRemaining").asInt(),state.path("newFree").asInt(),pages));originalStates++;
  }
  CompleteRound round=new CompleteRound(spins);CORE.validateRound(round);ORACLE.analyze(round);return round;
 }
 private static void accumulate(CompleteRound round,Map<String,Stats> stats,Map<String,TreeMap<Integer,Integer>> joint){
  ResultUtil.Analysis analysis=ORACLE.analyze(round);String roundLabel=analysis.outcome().name();
  joint.computeIfAbsent("roundSpins:"+roundLabel,k->new TreeMap<>()).merge(analysis.spinCount(),1,Integer::sum);
  joint.computeIfAbsent("roundPages:"+roundLabel,k->new TreeMap<>()).merge(analysis.cascadeCount(),1,Integer::sum);
  joint.computeIfAbsent("roundPayout:"+roundLabel,k->new TreeMap<>()).merge(bin(analysis.payoutUnits()),1,Integer::sum);
  for(int s=0;s<round.spins().size();s++){
   var spin=round.spins().get(s);int amount=0;for(int p=0;p<spin.cascades().size();p++)amount+=ORACLE.recomputePage(spin.cascades().get(p).symbols(),p,s>0);
   String label=s==0&&round.spins().size()>1?"paid_trigger":(s==0?"paid":"free")+(amount>0?"_win":"_loss");
   joint.computeIfAbsent("pages:"+label,k->new TreeMap<>()).merge(spin.cascades().size(),1,Integer::sum);
   joint.computeIfAbsent("payout:"+label,k->new TreeMap<>()).merge(bin(amount),1,Integer::sum);
   for(int p=0;p<spin.cascades().size();p++){
    var page=spin.cascades().get(p);int[] symbols=page.symbols();boolean[] gold=page.gold();
    int scatters=0,goldCount=0,wilds=0;
    for(int pos=0;pos<20;pos++){if(symbols[pos]==9)scatters++;if(symbols[pos]==10)wilds++;if(gold[pos])goldCount++;}
    String stage=label+(p==0?":initial":":refill");
    joint.computeIfAbsent("scatter:"+stage,k->new TreeMap<>()).merge(scatters,1,Integer::sum);
    joint.computeIfAbsent("gold:"+stage,k->new TreeMap<>()).merge(goldCount,1,Integer::sum);
    joint.computeIfAbsent("wild:"+stage,k->new TreeMap<>()).merge(wilds,1,Integer::sum);
    String key=stage;Stats a=stats.computeIfAbsent(key,k->new Stats());a.pages++;
    int[] predecessors=p==0?null:CORE.predecessorPositions(spin.cascades().get(p-1),page);
    for(int i=0;i<20;i++)if(predecessors==null||predecessors[i]<0){a.cells++;a.symbols[symbols[i]]++;if(gold[i])a.gold++;}
   }
  }
 }
 private static int bin(int units){int[] limits={0,10,25,50,100,250,500,1000,2500,10000};for(int i=0;i<limits.length;i++)if(units<=limits[i])return i;return limits.length;}
 private static double ratio(long numerator,long denominator){return denominator==0?0:(double)numerator/denominator;}
 private static double ks(TreeMap<Integer,Integer> a,TreeMap<Integer,Integer>b){
  long na=a.values().stream().mapToInt(Integer::intValue).sum(),nb=b.values().stream().mapToInt(Integer::intValue).sum(),ca=0,cb=0;double max=0;
  Set<Integer> keys=new TreeSet<>(a.keySet());keys.addAll(b.keySet());
  for(int key:keys){ca+=a.getOrDefault(key,0);cb+=b.getOrDefault(key,0);max=Math.max(max,Math.abs(ratio(ca,na)-ratio(cb,nb)));}return max;
 }
 private static final class Stats{
  long pages,cells,gold;long[] symbols=new long[11];
  Map<String,Object> report(){Map<String,Object> percentages=new TreeMap<>(),counts=new TreeMap<>();for(int s=1;s<=10;s++){counts.put(String.valueOf(s),symbols[s]);percentages.put(String.valueOf(s),100*ratio(symbols[s],cells));}return Map.of("pages",pages,"denominatorCells",cells,"symbolCounts",counts,"symbolPercent",percentages,"goldCells",gold);}
 }
 private static String sha(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
}
