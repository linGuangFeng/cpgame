package com.cpgame.batchd.treasurehunt.loader;

import com.cpgame.batchd.treasurehunt.core.GameRuleCore;
import com.cpgame.batchd.treasurehunt.core.MinimalRoundFactCodec;
import com.cpgame.batchd.treasurehunt.core.RedisClient;
import com.cpgame.batchd.treasurehunt.core.ResultUtil;
import com.cpgame.batchd.treasurehunt.core.RoundFactory;
import com.cpgame.batchd.treasurehunt.core.RoundVerifier;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class RedisLoader {
    private RedisLoader(){}
    public static void main(String[] args)throws Exception{Path config=Path.of(args.length==0?"generator.properties":args[0]).toAbsolutePath();Properties p=new Properties();try(InputStream in=Files.newInputStream(config)){p.load(in); LoaderLimits.checkKeys(p);}Settings s=Settings.read(p);if(s.gameId <= 0)throw new IllegalArgumentException("game-id must be 1810");SecureRandom secureRandom=new SecureRandom();RoundFactory factory=new RoundFactory(secureRandom);Map<Integer,List<String>> normal=new LinkedHashMap<>(),special=new LinkedHashMap<>();int wins=0,streak=0;long attempts=0;
        EntrySchedule schedule=new EntrySchedule(s.normalCount,s.specialCount);int pending=0;
        java.util.Set<Integer> normalRatios=new java.util.HashSet<>(),specialRatios=new java.util.HashSet<>();
        try(RedisClient redis=new RedisClient(s.host,s.port,s.username,s.password,s.database,s.ssl,s.connectTimeout,s.socketTimeout)) {
            redis.ping();
            for(int phase;(phase=schedule.next())>=0;) {
                LoaderLimits.checkAttempts(++attempts,(long)s.normalCount+s.specialCount);
                boolean mary=phase==1;
                var r=mary?(secureRandom.nextDouble()<s.treasureRatio?factory.treasureHunt():factory.allReelsMultiplier())
                        :factory.ordinary(s.outputLimits().accepts(false,0)&&secureRandom.nextDouble()<s.normalLossRatio?GameRuleCore.Outcome.LOSS:GameRuleCore.Outcome.WIN);
                RoundVerifier.verify(r,Integer.MAX_VALUE,Integer.MAX_VALUE);
                String member=MinimalRoundFactCodec.encode(r);var e=ResultUtil.calculate(member);
                if(!s.outputLimits().accepts(mary,e.totalUnits())||longest(r)>s.maxConsecutiveWins)continue;
                (mary?special:normal).computeIfAbsent(e.totalUnits(),k->new ArrayList<>()).add(member);
                (mary?specialRatios:normalRatios).add(e.totalUnits());
                if(!mary&&e.outcome()==GameRuleCore.Outcome.WIN)wins++;
                schedule.accepted(phase);
                if(++pending>=s.batchSize){flush(redis,s,normal,special);pending=0;}
            }
            flush(redis,s,normal,special);
        }
        System.out.printf("PASS rulesHash=%s normal=%d win=%d special=%d normalBuckets=%d specialBuckets=%d%n",GameRuleCore.RULES_HASH,s.normalCount,wins,s.specialCount,normalRatios.size(),specialRatios.size());
    }
    private static void flush(RedisClient redis,Settings s,Map<Integer,List<String>> normal,Map<Integer,List<String>> special) {
        redis.append(normalIndex(s.gameId),buckets(false,normal,s.maxMembers,s.gameId),s.maxMembers,s.batchSize);
        redis.append(specialIndex(s.gameId),buckets(true,special,s.outputLimits().specialCap,s.gameId),s.outputLimits().specialCap,s.batchSize);
        normal.clear();special.clear();
    }
    private static int longest(GameRuleCore.CompleteRound r){int n=0,max=0;for(var step:GameRuleCore.evaluate(r).steps()){n=step.stepUnits()>0?n+1:0;max=Math.max(max,n);}return max;}
    private static List<RedisClient.Bucket>buckets(boolean mary,Map<Integer,List<String>>source,int limit,int gid){List<RedisClient.Bucket>out=new ArrayList<>();for(var entry:source.entrySet()){String key=mary?String.format("MaryLog:%09d:%06d",gid,entry.getKey()):String.format("BetLog:0%08d:%06d",gid,entry.getKey());out.add(new RedisClient.Bucket(key,entry.getKey(),List.copyOf(entry.getValue())));}return out;}
    public static String normalIndex(int gid){return String.format("PerKeyList_%09d",gid);}public static String specialIndex(int gid){return String.format("MaryKeyList_%09d",gid);}
    private record Settings(String host,int port,String username,String password,int database,boolean ssl,int connectTimeout,int socketTimeout,int gameId,int normalCount,int specialCount,int batchSize,int maxMembers,int maxConsecutiveWins,int normalMax,int specialMax,double normalLossRatio,double treasureRatio, LoaderLimits outputLimits){
        Settings(String host,int port,String username,String password,int database,boolean ssl,int connectTimeout,int socketTimeout,int gameId,int normalCount,int specialCount,int batchSize,int maxMembers,int maxConsecutiveWins,int normalMax,int specialMax,double normalLossRatio,double treasureRatio) { this(host, port, username, password, database, ssl, connectTimeout, socketTimeout, gameId, normalCount, specialCount, batchSize, maxMembers, maxConsecutiveWins, normalMax, specialMax, normalLossRatio, treasureRatio, new LoaderLimits(new java.util.Properties())); }
static Settings read(Properties p){for(String key:p.stringPropertyNames())if(key.startsWith("generation.symbol."))throw new IllegalArgumentException("该配置不控制当前联合模型，已从正式配置移除: "+key);String host=req(p,"redis.host"),username=p.getProperty("redis.username","").strip(),password=p.getProperty("redis.password","");int port=positive(p,"redis.port"),database=integer(p,"redis.database"),connect=positive(p,"redis.connect-timeout-ms"),socket=positive(p,"redis.socket-timeout-ms"),gid=positive(p,"redis.game-id"),normal=positive(p,"generation.normal-count"),special=nonnegative(p,"generation.special-count"),batch=positive(p,"generation.batch-size"),members=positive(p,"generation.max-members-per-multiplier"),streak=positive(p,"generation.max-consecutive-wins"),normalMax=positive(p,"generation.normal-max-win-multiplier"),specialMax=positive(p,"generation.special-max-win-multiplier");boolean ssl=Boolean.parseBoolean(req(p,"redis.ssl"));double loss=ratio(p,"generation.normal-loss-ratio"),treasure=ratio(p,"generation.treasure-hunt-ratio");return new Settings(host,port,username,password,database,ssl,connect,socket,gid,normal,special,batch,members,streak,normalMax,specialMax,loss,treasure, new LoaderLimits(p));}}
    private static String req(Properties p,String key){String v=p.getProperty(key);if(v==null||v.isBlank())throw new IllegalArgumentException("missing "+key);return v.strip();}private static int integer(Properties p,String key){return Integer.parseInt(req(p,key));}private static int positive(Properties p,String key){int v=integer(p,key);if(v<=0)throw new IllegalArgumentException(key+" must be positive");return v;}private static int nonnegative(Properties p,String key){int v=integer(p,key);if(v<0)throw new IllegalArgumentException(key+" must be nonnegative");return v;}private static double ratio(Properties p,String key){double v=Double.parseDouble(req(p,key));if(v<=0||v>=1)throw new IllegalArgumentException(key+" must be between 0 and 1");return v;}
}
