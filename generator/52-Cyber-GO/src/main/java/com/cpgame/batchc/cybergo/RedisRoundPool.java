package com.cpgame.batchc.cybergo;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.List;
/** Only claims pre-generated complete rounds. Pool misses are explicit errors. */
public final class RedisRoundPool {
 public static final long GAME_ID=8000052;
 public static final String INDEX=String.format("PerKeyList_%09d", GAME_ID);
 public static final String SPECIAL_INDEX=String.format("MaryKeyList_%09d", GAME_ID);
 public static String list(String multiplier) {
  int ratio=Integer.parseInt(multiplier);
  return String.format("BetLog:0%08d:%06d", GAME_ID, ratio);
 }
 public static String specialList(String multiplier) {
  int ratio=Integer.parseInt(multiplier);
  return String.format("MaryLog:%09d:%06d", GAME_ID, ratio);
 }
 private final GeneratorConfig config;
 private final java.util.random.RandomGenerator random;
 private final String namespace;
 private final GameRuleCore core;
 public RedisRoundPool(GeneratorConfig config,GameRuleCore core) { this(config,core,"CyberGo:52:v3",new SecureRandom()); }
 // Package-private integration seam: tests use an isolated game-52 namespace on the same Redis DB.
 RedisRoundPool(GeneratorConfig config,GameRuleCore core,String namespace,java.util.random.RandomGenerator random) {
  if(!namespace.startsWith("CyberGo:52:"))throw new IllegalArgumentException("Namespace must belong to game 52");
  this.config=config;this.core=core;this.namespace=namespace;this.random=java.util.Objects.requireNonNull(random);
 }
 public CyberGoModels.CompleteRound claim() throws IOException {
  boolean win=random.nextBoolean();
  try(RedisConnection redis=RedisConnection.connect(config)) {
   String multiplier="0";
   if(win) {
    Object value=redis.command("ZRANGE", INDEX,"0","-1");
    if(!(value instanceof List<?> values)||values.isEmpty()) throw new IOException("POOL_EMPTY_WIN");
    List<String> positives=new java.util.ArrayList<>();
    for(Object item:values){
     String token=item.toString();
     if(token.matches("[1-9][0-9]*")) positives.add(token);
    }
    if(positives.isEmpty()) throw new IOException("POOL_EMPTY_WIN");
    multiplier=positives.get(random.nextInt(positives.size()));
   }
   Object len=redis.command("LLEN", list(multiplier));
   long n=len instanceof Number number?number.longValue():Long.parseLong(String.valueOf(len));
   if(n<=0) throw new IOException("POOL_EMPTY_"+(win?"WIN":"LOSS"));
   Object member=redis.command("LINDEX", list(multiplier), Integer.toString(random.nextInt((int)Math.min(n,Integer.MAX_VALUE))));
   if(member==null) throw new IOException("POOL_EMPTY_"+(win?"WIN":"LOSS"));
   var facts=new MinimalFactCodec().decodeFromRedis(member.toString().getBytes(StandardCharsets.US_ASCII));
   var round=core.rebuild(facts); var result=ResultUtil.reverse(round);
   if(result.totalWin().compareTo(CyberGoRules.MINIMUM_BET.multiply(new java.math.BigDecimal(multiplier)))!=0) throw new IOException("POOL_MEMBER_MULTIPLIER_MISMATCH");
   return round;
  }
 }
}
