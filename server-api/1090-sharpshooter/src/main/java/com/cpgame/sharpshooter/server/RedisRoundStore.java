package com.cpgame.sharpshooter.server;
import com.cpgame.sharpshooter.core.*;
import redis.clients.jedis.*;
import java.security.SecureRandom;
import java.util.*;

final class RedisRoundStore implements AutoCloseable {
 enum Selection {LOSS,WIN,SPECIAL,ANY}
 record Claim(String member,CompleteRound round,ResultUtil.Analysis analysis){}
 static final class CacheEmptyException extends RuntimeException{CacheEmptyException(String message){super(message);}}
 private final Jedis jedis;private final String game;
 private final RoundCodec codec=new RoundCodec();private final GameRuleCore rules=new GameRuleCore();
 private final ResultUtil util=new ResultUtil(rules);private final SecureRandom random=new SecureRandom();
 RedisRoundStore(Properties p){
  String host=p.getProperty("redis.host");int port=Integer.parseInt(p.getProperty("redis.port","8021")),db=Integer.parseInt(p.getProperty("redis.database","0"));
  if(!"18.234.101.161".equals(host)||port!=8021||db < 0)throw new IllegalArgumentException("authorized Redis endpoint is 18.234.101.161:8021 DB15");
  game=p.getProperty("redis.game-id","8001090");RedisKeys.prefix(game);
  DefaultJedisClientConfig.Builder cfg=DefaultJedisClientConfig.builder().database(db)
   .connectionTimeoutMillis(Integer.parseInt(p.getProperty("redis.connect-timeout-ms","5000")))
   .socketTimeoutMillis(Integer.parseInt(p.getProperty("redis.socket-timeout-ms","10000")));
  String user=p.getProperty("redis.username","").trim(),pass=p.getProperty("redis.password","").trim();
  if(!user.isEmpty())cfg.user(user);if(!pass.isEmpty())cfg.password(pass);
  jedis=new Jedis(host,port,cfg.build());jedis.connect();
 }
 synchronized Claim claim(Selection selection,Integer multiplier){
  boolean loss=selection==Selection.LOSS||(selection==Selection.ANY&&random.nextBoolean());
  boolean includeNormalWin=selection==Selection.WIN||(selection==Selection.ANY&&!loss);
  boolean includeSpecial=selection==Selection.SPECIAL||(selection==Selection.ANY&&!loss);
  List<int[]> available=new ArrayList<>();
  if(loss) collect(available,false,multiplier,true);
  if(includeNormalWin) collect(available,false,multiplier,false);
  if(includeSpecial) collect(available,true,multiplier,false);
  if(available.isEmpty())throw new CacheEmptyException("selected "+(loss?"loss":"win")+" pool has no complete member");
  int[] chosen=available.get(random.nextInt(available.size()));
  boolean special=chosen[0]==1;int chosenMultiplier=chosen[1];
  String list=special?RedisKeys.maryList(game,chosenMultiplier):RedisKeys.normalList(game,chosenMultiplier);
  long len=jedis.llen(list);
  if(len<=0)throw new CacheEmptyException("selected member was concurrently exhausted");
  String member=jedis.lindex(list, random.nextInt((int)Math.min(len,Integer.MAX_VALUE)));
  if(member==null)throw new CacheEmptyException("selected member was concurrently exhausted");
  CompleteRound round=codec.decode(member);rules.validateRound(round);ResultUtil.Analysis analysis=util.analyze(round);
  if(analysis.integerMultiplier()!=chosenMultiplier)throw new IllegalStateException("Redis member does not match its pool");
  if(special!=RedisKeys.special(analysis.outcome()))throw new IllegalStateException("Redis member does not match its pool");
  return new Claim(member,round,analysis);
 }
 private void collect(List<int[]> available,boolean special,Integer multiplier,boolean lossOnly){
  String index=special?RedisKeys.maryIndex(game):RedisKeys.normalIndex(game);
  List<String> entries=multiplier==null?jedis.zrange(index,0,-1):jedis.zrangeByScore(index,multiplier,multiplier);
  for(String entry:entries){
   int multiple=Integer.parseInt(entry);
   if(lossOnly && multiple!=0) continue;
   if(!lossOnly && multiple<=0) continue;
   String list=special?RedisKeys.maryList(game,multiple):RedisKeys.normalList(game,multiple);
   if(jedis.llen(list)>0) available.add(new int[]{special?1:0,multiple});
  }
 }
 @Override public void close(){jedis.close();}
}
