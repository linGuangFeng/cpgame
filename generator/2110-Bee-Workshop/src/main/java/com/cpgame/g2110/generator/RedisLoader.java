package com.cpgame.g2110.generator;
import com.cpgame.g2110.core.*;import redis.clients.jedis.*;import java.io.*;import java.nio.charset.StandardCharsets;import java.nio.file.*;import java.util.*;
public final class RedisLoader {
 public static void main(String[]args)throws Exception{
  Properties p=load(args);String host=p.getProperty("redis.host","192.168.10.3"),game=p.getProperty("redis.game-id","2110");
  int port=Integer.parseInt(p.getProperty("redis.port","6379")),db=Integer.parseInt(p.getProperty("redis.database","15"));
  int cap=Integer.parseInt(p.getProperty("generation.max-members-per-multiplier", p.getProperty("redis.max-members-per-bucket","300")));
  int specialCap=Integer.parseInt(p.getProperty("generation.special-max-members-per-multiplier","100"));
  RedisKeys.requireGame(game);
  if(host.isBlank()||port<1||port>65535||db<0||cap<=0||specialCap<=0)throw new IllegalArgumentException("Bee Workshop requires Redis 192.168.10.3:6379 DB15 and a positive bucket cap");
  if(p.stringPropertyNames().stream().anyMatch(k->k.toLowerCase(Locale.ROOT).contains("seed")))throw new IllegalArgumentException("Formal loader does not accept a seed");
for(String key:p.stringPropertyNames())if(key.startsWith("generation.symbol.")||key.startsWith("generation.entry-switch-every"))throw new IllegalArgumentException("该配置不控制当前联合模型，已从正式配置移除: "+key);
  LoaderLimits limits=new LoaderLimits(p);
  int batchSize=Integer.parseInt(p.getProperty("generation.batch-size","50")),maxWins=Integer.parseInt(p.getProperty("generation.max-consecutive-wins","10")),maxFree=Integer.parseInt(p.getProperty("generation.max-mary-spins","16"));
  if(batchSize<1||maxWins<1||maxFree<1)throw new IllegalArgumentException("Invalid batch/round limits");
  GameRuleCore rules=new GameRuleCore();RoundFactory factory=new RoundFactory(rules);ResultUtil util=new ResultUtil(rules);MinimalRoundFactCodec codec=new MinimalRoundFactCodec();
  Map<GameRuleCore.RoundKind,Integer>targets=new LinkedHashMap<>();
  int normal=Integer.parseInt(p.getProperty("generation.normal-count","800"));
  int special=Integer.parseInt(p.getProperty("generation.special-count","400"));
  targets.put(GameRuleCore.RoundKind.ORDINARY_LOSS,Integer.parseInt(p.getProperty("generation.ordinary-loss", Integer.toString(normal/2))));
  targets.put(GameRuleCore.RoundKind.ORDINARY_WIN,Integer.parseInt(p.getProperty("generation.ordinary-win", Integer.toString(normal-normal/2))));
  targets.put(GameRuleCore.RoundKind.MYSTERY_BOX,Integer.parseInt(p.getProperty("generation.mystery-box", Integer.toString(special/2))));
  targets.put(GameRuleCore.RoundKind.FREE_STICKY_SYMBOLS,Integer.parseInt(p.getProperty("generation.free-sticky-symbols", Integer.toString(special-special/2))));
  if(!limits.accepts(false,0)){
   int loss=targets.get(GameRuleCore.RoundKind.ORDINARY_LOSS);
   targets.put(GameRuleCore.RoundKind.ORDINARY_LOSS,0);
   if(!p.containsKey("generation.ordinary-loss"))targets.merge(GameRuleCore.RoundKind.ORDINARY_WIN,loss,Integer::sum);
  }
  if(targets.values().stream().anyMatch(n->n<0))throw new IllegalArgumentException("negative generation target");
  Map<String,Integer>written=new TreeMap<>();
  DefaultJedisClientConfig.Builder client=DefaultJedisClientConfig.builder().database(db).ssl(Boolean.parseBoolean(p.getProperty("redis.ssl","false"))).connectionTimeoutMillis(Integer.parseInt(p.getProperty("redis.connect-timeout-ms","5000"))).socketTimeoutMillis(Integer.parseInt(p.getProperty("redis.socket-timeout-ms","30000")));
  if(!p.getProperty("redis.username","").isBlank())client.user(p.getProperty("redis.username"));
  if(!p.getProperty("redis.password","").isEmpty())client.password(p.getProperty("redis.password"));
  long attempts=0;
  try(Jedis j=new Jedis(host,port,client.build())){
   j.connect();String password=p.getProperty("redis.password","").trim();if(!password.isEmpty())j.auth(password);j.select(db);
   if(Boolean.parseBoolean(p.getProperty("redis.clear-game-prefix","false")))clearKnownBuckets(j);
   Transaction tx=null;int pending=0;
   for(var target:targets.entrySet())for(int i=0;i<target.getValue();i++){
    LoaderLimits.checkAttempts(++attempts,(long)normal+special);
    var round=factory.generate(target.getKey());rules.validate(round);
    long coreUnits=round.steps().stream().mapToLong(s->rules.payoutUnits(s.board())).sum();
    if(coreUnits!=util.totalUnits(round))throw new IllegalStateException("independent ResultUtil disagrees");
    String member=codec.encode(round);if(!member.equals(codec.encode(codec.decode(member))))throw new IllegalStateException("codec round trip");
    int multiplier=util.integerMultiplier(round);boolean specialRound=RedisKeys.special(target.getKey());
    if(!limits.accepts(specialRound,multiplier)){i--;continue;}
    int streak=0,longest=0;for(var step:round.steps()){streak=rules.payoutUnits(step.board())>0?streak+1:0;longest=Math.max(longest,streak);}
    if(longest>maxWins||round.kind()==GameRuleCore.RoundKind.FREE_STICKY_SYMBOLS&&round.steps().size()-1>maxFree){i--;continue;}
    String index=RedisKeys.index(specialRound),list=RedisKeys.list(specialRound,multiplier);
    if(tx==null)tx=j.multi();tx.zadd(index,multiplier,Integer.toString(multiplier));
    tx.rpush(list,member);tx.ltrim(list,-(specialRound?specialCap:cap),-1);if(++pending>=batchSize){if(tx.exec()==null)throw new IllegalStateException("Redis EXEC aborted");tx=null;pending=0;}
    written.merge(target.getKey().name(),1,Integer::sum);
   }
   if(tx!=null&&tx.exec()==null)throw new IllegalStateException("Redis EXEC aborted");
  }
  System.out.println("loaded gid="+game+" db="+db+" complete rounds="+written+" rulesHash="+GameRuleCore.RULES_HASH);
 }
 private static void clearKnownBuckets(Jedis j){
  for(boolean special:new boolean[]{false,true}){
   String index=RedisKeys.index(special);
   for(String member:j.zrange(index,0,-1))j.del(RedisKeys.list(special,Integer.parseInt(member)));
   j.del(index);
  }
 }
 private static Properties load(String[]args)throws Exception{
  Path explicit=null;
  for(int i=0;i<args.length;i++){
   String arg=args[i];
   if(arg.equals("--no-pause"))continue;
   if(arg.equals("--config")&&i+1<args.length)explicit=Path.of(args[++i]);
   else if(arg.startsWith("--config="))explicit=Path.of(arg.substring(9));
   else if(!arg.startsWith("--")&&explicit==null)explicit=Path.of(arg);
   else throw new IllegalArgumentException("unsupported loader argument: "+arg);
  }
  Path code=Path.of(RedisLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI());
  Path file=explicit!=null?explicit:(Files.isDirectory(code)?code:code.getParent()).resolve("generator.properties");
  Properties properties=new Properties();try(Reader reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){properties.load(reader); LoaderLimits.checkKeys(properties);}return properties;
 }
}
