package com.cpgame.wukong.loader;

import com.cpgame.wukong.core.*;
import com.cpgame.wukong.redis.*;
import java.io.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

/** SecureRandom 正式 Loader：自然生成完整局，由独立 ResultUtil 分类后原子追加并裁剪。 */
public final class RedisLoader {
    private static final int SOURCE_GAME_ID=2210;
    private RedisLoader(){}
    public static void main(String[] args)throws Exception{
        Path path=Path.of(args.length==0?"generator.properties":args[0]).toAbsolutePath().normalize();Properties p=new Properties();try(InputStream in=Files.newInputStream(path)){p.load(in); LoaderLimits.checkKeys(p);}validateConfigKeys(p);Config c=Config.from(p);
        GameRuleCore rules=new GameRuleCore();ResultUtil results=new ResultUtil(rules);RoundCodec codec=new RoundCodec(rules);RoundGenerator generator=new RoundGenerator(new SecureRandom(),p);
        Map<Integer,Deque<String>> normal=new TreeMap<>(),special=new TreeMap<>();int acceptedNormal=0,acceptedSpecial=0,consecutiveWins=0;long attempts=0;EnumSet<CompleteRound.Outcome> seen=EnumSet.noneOf(CompleteRound.Outcome.class);
        long maxAttempts=Math.max(10000L,((long)c.normalCount+c.specialCount)*200L);
        while((acceptedNormal<c.normalCount||acceptedSpecial<c.specialCount)&&attempts++<maxAttempts){
            CompleteRound round=acceptedNormal==0&&c.outputLimits().accepts(false,0)?generator.generateLoss():generator.generate();ResultUtil.Evaluation ev=results.evaluate(round);CompleteRound.Outcome outcome=rules.classify(round);boolean isSpecial=rules.isSpecial(round);int multiplier=ev.totalMultiplier();int chain=0,maxChain=0;for(int value:ev.resultMultipliers()){chain=value>0?chain+1:0;maxChain=Math.max(maxChain,chain);}if(maxChain>c.maxConsecutiveWins)continue;
            if(!c.outputLimits().accepts(isSpecial,multiplier)||multiplier>(isSpecial?c.specialMaxMultiplier:c.normalMaxMultiplier))continue;
            if(isSpecial&&acceptedSpecial>=c.specialCount||!isSpecial&&acceptedNormal>=c.normalCount)continue;Map<Integer,Deque<String>> target=isSpecial?special:normal;Deque<String> bucket=target.computeIfAbsent(multiplier,k->new ArrayDeque<>());
            String member=codec.encode(round);if(codec.verifyMultiplier(member)!=multiplier)throw new IllegalStateException("codec/result mismatch");bucket.addLast(member);int retained=isSpecial?c.outputLimits().specialCap:c.capacity;while(bucket.size()>retained)bucket.removeFirst();seen.add(outcome);if(isSpecial)acceptedSpecial++;else acceptedNormal++;
        }
        if(acceptedNormal<c.normalCount||acceptedSpecial<c.specialCount)throw new IllegalStateException("generation target exhausted without multiplier pursuit");
        
        try(RedisClient redis=RedisClient.connect(c.host,c.port,c.user,c.password,c.database,c.ssl,c.connectMs,c.socketMs)){write(redis,c,false,normal);write(redis,c,true,special);verify(redis,c,false,normal,codec);verify(redis,c,true,special,codec);}
        System.out.printf("COMPLETE_PASS sourceGameId=%d redisGameId=%d rulesHash=%s normal=%d special=%d normalBuckets=%d specialBuckets=%d attempts=%d%n",SOURCE_GAME_ID,c.gameId,GameRuleCore.RULES_HASH,acceptedNormal,acceptedSpecial,normal.size(),special.size(),attempts);
    }
    private static void write(RedisClient redis,Config c,boolean special,Map<Integer,Deque<String>> buckets)throws IOException{String index=special?RedisKeys.specialIndex(c.gameId):RedisKeys.normalIndex(c.gameId);int cap=special?c.outputLimits().specialCap:c.capacity;for(var e:buckets.entrySet()){String ratio=Integer.toString(e.getKey()),key=special?RedisKeys.specialList(c.gameId,e.getKey()):RedisKeys.normalList(c.gameId,e.getKey());List<String> members=new ArrayList<>(e.getValue());for(int from=0;from<members.size();from+=c.batchSize){int to=(int)Math.min(members.size(),(long)from+c.batchSize);redis.command("MULTI");redis.command("ZADD",index,ratio,ratio);for(String member:members.subList(from,to))redis.command("RPUSH",key,member);redis.command("LTRIM",key,Integer.toString(-cap),"-1");if(!(redis.command("EXEC") instanceof List<?>))throw new IOException("Redis EXEC failed");}}}
    private static void verify(RedisClient redis,Config c,boolean special,Map<Integer,Deque<String>> buckets,RoundCodec codec)throws IOException{for(int multiplier:buckets.keySet()){String key=special?RedisKeys.specialList(c.gameId,multiplier):RedisKeys.normalList(c.gameId,multiplier);Object value=redis.command("LINDEX",key,"-1");if(value==null||codec.verifyMultiplier(value.toString())!=multiplier)throw new IllegalStateException("Redis verification failed: "+key);}}
    private static void validateConfigKeys(Properties p){Set<String> exact=Set.of("redis.host","redis.port","redis.username","redis.password","redis.database","redis.ssl","redis.connect-timeout-ms","redis.socket-timeout-ms","redis.game-id","generation.normal-count","generation.special-count","generation.batch-size","generation.max-members-per-multiplier","generation.special-max-members-per-multiplier","generation.max-consecutive-wins","generation.normal-min-win-multiplier","generation.normal-max-win-multiplier","generation.special-min-win-multiplier","generation.special-max-win-multiplier","weights.mode","weights.initial.none","weights.initial.x2","weights.initial.x5","weights.initial.respin","weights.respin-redeal");for(String key:p.stringPropertyNames())if(!exact.contains(key)&&!key.startsWith("generation.symbol."))throw new IllegalArgumentException("unsupported formal configuration property "+key);}
    private record Config(String host,int port,String user,String password,int database,boolean ssl,int connectMs,int socketMs,int gameId,int normalCount,int specialCount,int batchSize,int capacity,int maxConsecutiveWins,int normalMaxMultiplier,int specialMaxMultiplier, LoaderLimits outputLimits){
        Config(String host,int port,String user,String password,int database,boolean ssl,int connectMs,int socketMs,int gameId,int normalCount,int specialCount,int batchSize,int capacity,int maxConsecutiveWins,int normalMaxMultiplier,int specialMaxMultiplier) { this(host, port, user, password, database, ssl, connectMs, socketMs, gameId, normalCount, specialCount, batchSize, capacity, maxConsecutiveWins, normalMaxMultiplier, specialMaxMultiplier, new LoaderLimits(new java.util.Properties())); }

        static Config from(Properties p){Config c=new Config(req(p,"redis.host"),integer(p,"redis.port"),p.getProperty("redis.username","").trim(),p.getProperty("redis.password",""),integer(p,"redis.database"),bool(p,"redis.ssl"),integer(p,"redis.connect-timeout-ms"),integer(p,"redis.socket-timeout-ms"),integer(p,"redis.game-id"),integer(p,"generation.normal-count"),integer(p,"generation.special-count"),integer(p,"generation.batch-size"),integer(p,"generation.max-members-per-multiplier"),integer(p,"generation.max-consecutive-wins"),integer(p,"generation.normal-max-win-multiplier"),integer(p,"generation.special-max-win-multiplier"), new LoaderLimits(p));if(c.gameId<1||c.database<0||c.normalCount<0||c.specialCount<0||c.normalCount>Integer.MAX_VALUE||c.specialCount>Integer.MAX_VALUE||c.batchSize<1||c.capacity<1||c.maxConsecutiveWins<1)throw new IllegalArgumentException("invalid generator contract");return c;}
        private static String req(Properties p,String k){String v=p.getProperty(k);if(v==null||v.isBlank())throw new IllegalArgumentException("missing "+k);return v.trim();}private static int integer(Properties p,String k){return Integer.parseInt(req(p,k));}private static boolean bool(Properties p,String k){String v=req(p,k);if(!v.equals("true")&&!v.equals("false"))throw new IllegalArgumentException("invalid boolean "+k);return Boolean.parseBoolean(v);}
    }
}
