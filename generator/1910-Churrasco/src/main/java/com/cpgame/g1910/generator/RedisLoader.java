package com.cpgame.g1910.generator;

import com.cpgame.g1910.core.*;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;

/** Generates complete verified rounds and atomically loads the configured Redis pools. */
public final class RedisLoader {
    private RedisLoader() { }
    public static void main(String[] args)throws Exception{
        Path file=configPath(args);Properties p=new Properties();try(InputStream in=Files.newInputStream(file)){p.load(in); LoaderLimits.checkKeys(p);}
        LoaderLimits limits=new LoaderLimits(p);long attempts=0;
        int gid=integer(p,"redis.game-id"),normalCount=integer(p,"generation.normal-count"),specialCount=integer(p,"generation.special-count"),batchSize=integer(p,"generation.batch-size"),maxMembers=integer(p,"generation.max-members-per-multiplier"),maxConsecutiveWins=integer(p,"generation.max-consecutive-wins"),maxUnits=integer(p,"generation.max-win-units");
        if(gid <= 0||normalCount<0||specialCount<0||batchSize<1||maxMembers<1||maxConsecutiveWins<1)throw new IllegalArgumentException("invalid required generation contract");
        int[]paidWeights=weights(p,"generation.symbol."),freeWeights=weights(p,"generation.free-symbol.");
        var secureRandom=new SecureRandom();var factory=new RoundFactory(secureRandom,paidWeights,freeWeights);Map<Integer,List<String>>normal=new TreeMap<>(),special=new TreeMap<>();
        int losses=0,wins=0,small=0,free=0,consecutive=0;
        for(int i=0;i<normalCount;i++){LoaderLimits.checkAttempts(++attempts,(long)normalCount+specialCount);
            GameRuleCore.CompleteRound round;
            int selector=secureRandom.nextInt(10000);
            if(selector<1743){round=factory.smallGame();consecutive=0;}
            else if(selector<3230){round=factory.ordinary(true);consecutive++;}
            else{round=factory.ordinary(false);consecutive=0;}
            if(!acceptsRound(round,false,limits,maxUnits,maxConsecutiveWins)){i--;continue;}
            if(selector<1743)small++;else if(selector<3230)wins++;else losses++;
            add(normal,round,maxUnits);
        }
        for(int i=0;i<specialCount;){
            LoaderLimits.checkAttempts(++attempts,(long)normalCount+specialCount);
            GameRuleCore.CompleteRound r;
            try { r=factory.freeReward(); }
            catch(RoundFactory.CandidateLimitException rejected) { continue; }
            if(!acceptsRound(r,true,limits,maxUnits,maxConsecutiveWins))continue;
            add(special,r,maxUnits);free++;i++;
        }
        try(var redis=open(p)){redis.ping();redis.append(normalIndex(gid),buckets(false,normal,gid),maxMembers,batchSize);redis.append(specialIndex(gid),buckets(true,special,gid),limits.specialCap,batchSize);}
        System.out.printf("LOAD_COMPLETE COMPLETE_PASS rulesHash=%s normal=%d loss=%d win=%d small=%d free=%d batch=%d redis=db15%n",GameRuleCore.RULES_HASH,normalCount,losses,wins,small,free,batchSize);
    }
    static boolean acceptsRound(GameRuleCore.CompleteRound round, boolean special,
                                LoaderLimits limits, int maxUnits, int maxConsecutiveWins) {
        int units=ResultUtil.totalUnits(MinimalRoundFactCodec.encode(round));
        return units<=maxUnits && limits.accepts(special,units) && longest(round)<=maxConsecutiveWins;
    }
    private static int longest(GameRuleCore.CompleteRound r){var e=GameRuleCore.evaluate(r);int n=e.paid().units()>0?1:0,max=n;for(var step:e.freeSteps()){n=step.units()>0?n+1:0;max=Math.max(max,n);}return max;}
    private static void add(Map<Integer,List<String>>out,GameRuleCore.CompleteRound round,int maxUnits){RoundVerifier.verify(round,maxUnits);String fact=MinimalRoundFactCodec.encode(round);int units=ResultUtil.totalUnits(fact);out.computeIfAbsent(units,k->new ArrayList<>()).add(fact);}
    private static List<RedisClient.Bucket>buckets(boolean special,Map<Integer,List<String>>source,int gid){List<RedisClient.Bucket>out=new ArrayList<>();for(var e:source.entrySet()){String key=special?String.format("MaryLog:%09d:%06d",gid,e.getKey()):String.format("BetLog:0%08d:%06d",gid,e.getKey());out.add(new RedisClient.Bucket(key,e.getKey(),List.copyOf(e.getValue())));}return out;}
    public static String normalIndex(int gid){return String.format("PerKeyList_%09d",gid);}public static String specialIndex(int gid){return String.format("MaryKeyList_%09d",gid);}
    private static RedisClient open(Properties p){return new RedisClient(required(p,"redis.host"),integer(p,"redis.port"),p.getProperty("redis.username",""),p.getProperty("redis.password",""),integer(p,"redis.database"),Boolean.parseBoolean(required(p,"redis.ssl")),integer(p,"redis.connect-timeout-ms"),integer(p,"redis.socket-timeout-ms"));}
    private static Path configPath(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("--config".equals(args[i]) && i + 1 < args.length) {
                return Path.of(args[i + 1]).toAbsolutePath();
            }
            if (args[i].startsWith("--config=")) {
                String value = args[i].substring("--config=".length());
                if (!value.isBlank()) return Path.of(value).toAbsolutePath();
            }
        }
        throw new IllegalArgumentException("--config generator.properties is required");
    }
    private static int[]weights(Properties p,String prefix){int[]out=new int[13];for(int symbol=1;symbol<=13;symbol++){out[symbol-1]=integer(p,prefix+symbol+".weight");if(out[symbol-1]<=0)throw new IllegalArgumentException("all current-game symbol weights must be positive");}return out;}
    private static String required(Properties p,String key){String value=p.getProperty(key);if(value==null||value.isBlank())throw new IllegalArgumentException("missing "+key);return value.strip();}private static int integer(Properties p,String key){return Integer.parseInt(required(p,key));}
}
