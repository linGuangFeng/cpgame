package com.cpgame.luckynightmarket;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
/** Explicitly authorized, atomic game-scoped replacement, after every new round is verified. */
public final class PoolInstaller {
 private static final List<String> PAYOUT_BANDS=List.of("0x","(0,5)x","[5,10)x","[10,50)x",">=50x");
 private static Map<String,Integer> emptyBands(){Map<String,Integer> result=new LinkedHashMap<>();for(String band:PAYOUT_BANDS)result.put(band,0);return result;}
 private static String payoutBand(long units){return units==0?"0x":units<25?"(0,5)x":units<50?"[5,10)x":units<250?"[10,50)x":">=50x";}
 private static void recordBand(Map<String,Integer> bands,Map<String,Map<String,Integer>> modeBands,RoundFact.Mode mode,long units){String band=payoutBand(units);bands.merge(band,1,Integer::sum);modeBands.computeIfAbsent(mode.name(),k->emptyBands()).merge(band,1,Integer::sum);}
 private static long verifiedUnits(RoundFact round){
  GameRuleCore.validate(round);long units=GameRuleCore.totalUnits(round);
  if(units!=ResultUtil.totalUnits(round))throw new IllegalStateException("Independent oracle mismatch before Redis write");
  String member=RoundCodec.encode(round);RoundFact decoded=RoundCodec.decode(member);
  RoundCodec.verifyEquivalent(round,decoded);
  if(!RoundCodec.encode(decoded).equals(member)||ResultUtil.totalUnits(decoded)!=units)throw new IllegalStateException("Codec roundtrip mismatch before Redis write");
  return units;
 }
 /** Pure preflight, with no Redis dependency. Inspect the complete retained replacement pool. */
 public static Map<String,Object> verifyCoverageBeforeRedisWrite(Properties p,Map<String,List<String>> pools){
  int gid=Integer.parseInt(p.getProperty("redis.game-id","2470"));if(gid!=2470)throw new IllegalArgumentException("Coverage audit is restricted to 2470");
  Map<String,Integer> modes=new TreeMap<>(),bands=emptyBands();Map<String,Map<String,Integer>> modeBands=new TreeMap<>();int retained=0,smallWins=0;
  for(var entry:pools.entrySet()){
   if(entry.getValue().isEmpty())throw new IllegalStateException("Empty retained bucket before Redis write: "+entry.getKey());
   for(String member:entry.getValue()){
    RoundFact round=RoundCodec.decode(member);long units=verifiedUnits(round);
    if(!GeneratorMain.key(GeneratorMain.pool(round.mode()),gid,units).equals(entry.getKey()))throw new IllegalStateException("Retained payout/key mismatch before Redis write: "+entry.getKey());
    modes.merge(round.mode().name(),1,Integer::sum);recordBand(bands,modeBands,round.mode(),units);retained++;
    if(round.mode()==RoundFact.Mode.ORDINARY_WIN&&units>0&&units<25)smallWins++;
   }
  }
  boolean requireSmall=Long.parseLong(p.getProperty("range.normal-min","1"))<=2&&Long.parseLong(p.getProperty("range.normal-max","375"))>=24;
  Map<String,Object> audit=Json.map("retainedMembers",retained,"retainedModes",modes,"retainedPayoutBands",bands,"retainedModePayoutBands",modeBands,"ordinarySmallWinCoverageRequired",requireSmall,"retainedOrdinarySmallWinMembers",smallWins,"payoutBandDenominator","complete round payout / paid total bet; total bet=5 units");
  if(modes.size()!=4)throw new IllegalStateException("Retained pool is missing a mode before Redis write: "+Json.stringify(audit));
  if(requireSmall&&smallWins==0)throw new IllegalStateException("Retained pool has no nonzero ordinary small win (1..24 units) before Redis write: "+Json.stringify(audit));
  audit.put("coverageVerifiedBeforeRedisWrite",true);audit.put("roundValidationCodecRoundtripAndIndependentUnits","PASS");return audit;
 }
 public static void main(String[] args)throws Exception{
  if(args.length!=2)throw new IllegalArgumentException("Usage: PoolInstaller generator.properties report.json");
  Properties p=new Properties();try(Reader in=Files.newBufferedReader(Path.of(args[0]),StandardCharsets.UTF_8)){p.load(in);}
  GeneratorMain.validateConfig(p);
  if(!p.getProperty("redis.host","192.168.10.3").equals("192.168.10.3")||Integer.parseInt(p.getProperty("redis.port","6379"))!=6379||Integer.parseInt(p.getProperty("redis.database","15"))!=15)throw new IllegalArgumentException("Replacement authorization is restricted to 192.168.10.3:6379 database 15");
  int gid=Integer.parseInt(p.getProperty("redis.game-id"));if(gid!=2470)throw new IllegalArgumentException("Replacement is restricted to 2470");
  int count=Integer.parseInt(p.getProperty("generation.count","10000"));DealingModel model=new DealingModel(p);
  Map<String,List<String>> pools=new TreeMap<>();Map<String,Integer> modes=new TreeMap<>(),generatedBands=emptyBands();Map<String,Map<String,Integer>> generatedModeBands=new TreeMap<>();
  for(int i=0;i<count;i++){
   RoundFact r=model.generateForPool(model.mode());long units=verifiedUnits(r);recordBand(generatedBands,generatedModeBands,r.mode(),units);
   GeneratorMain.Pool pool=GeneratorMain.pool(r.mode());String key=GeneratorMain.key(pool,gid,units);
   List<String> list=pools.computeIfAbsent(key,k->new ArrayList<>());list.add(RoundCodec.encode(r));
   int retain=GeneratorMain.retention(p,pool);if(list.size()>retain)list.remove(0);modes.merge(r.mode().name(),1,Integer::sum);
  }
  if(modes.size()!=4)throw new IllegalStateException("Random generation missed a mode; rerun without changing formal weights");
  // This must remain before constructing RedisClient or preparing any Redis transaction.
  Map<String,Object> coverage=verifyCoverageBeforeRedisWrite(p,pools);
  Set<String> oldKeys=new TreeSet<>();List<List<String>> commands=new ArrayList<>();commands.add(List.of("MULTI"));
  try(RedisClient redis=new RedisClient(p)){
   for(GeneratorMain.Pool pool:GeneratorMain.Pool.values()){
    String index=GeneratorMain.index(pool,gid);Object result=redis.command("ZRANGE",index,"0","-1");
    if(result instanceof List<?> values)for(Object value:values){String multiplier=value.toString();if(!multiplier.matches("[0-9]{1,6}"))throw new IllegalStateException("Unrecognized existing index member");oldKeys.add(GeneratorMain.key(pool,gid,Long.parseLong(multiplier)));}oldKeys.add(index);
   }
   for(String key:oldKeys)commands.add(List.of("DEL",key));
   for(var entry:pools.entrySet()){
    GeneratorMain.Pool pool=GeneratorMain.poolOfKey(entry.getKey());String units=Long.toString(Long.parseLong(entry.getKey().substring(entry.getKey().lastIndexOf(':')+1)));
    commands.add(List.of("ZADD",GeneratorMain.index(pool,gid),units,units));List<String> push=new ArrayList<>(List.of("RPUSH",entry.getKey()));push.addAll(entry.getValue());commands.add(push);
    int retain=GeneratorMain.retention(p,pool);commands.add(List.of("LTRIM",entry.getKey(),"-"+retain,"-1"));
   }
   commands.add(List.of("EXEC"));GeneratorMain.verifyTransaction(commands,redis.batch(commands));
   int members=0;for(var entry:pools.entrySet()){
    Object value=redis.command("LRANGE",entry.getKey(),"0","-1");if(!(value instanceof List<?> actual)||actual.size()!=entry.getValue().size())throw new IOException("Post-install length mismatch");
    for(Object encoded:actual){RoundFact r=RoundCodec.decode(encoded.toString());if(!GeneratorMain.key(GeneratorMain.pool(r.mode()),gid,ResultUtil.totalUnits(r)).equals(entry.getKey()))throw new IOException("Post-install key mismatch");members++;}
   }
   Map<String,Object> report=Json.map("gameId",gid,"redis",p.getProperty("redis.host")+":"+p.getProperty("redis.port")+"/"+p.getProperty("redis.database"),"generatedCompleteRounds",count,"generatedModes",modes,"generatedPayoutBands",generatedBands,"generatedModePayoutBands",generatedModeBands,"retainedMembers",members,"newBuckets",pools.size(),"replacedGameKeys",oldKeys.size(),"atomicReplacement",true,"independentPayoutAndKeyValidation","PASS","codec","LNM1 ASCII","rejectedSteps",model.rejectedSteps,"rejectedRounds",model.rejectedRounds);report.putAll(coverage);
   Files.writeString(Path.of(args[1]),Json.stringify(report)+"\n");System.out.println(Json.stringify(report));
  }
 }
}
