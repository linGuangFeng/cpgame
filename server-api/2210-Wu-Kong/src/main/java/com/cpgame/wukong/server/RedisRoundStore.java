package com.cpgame.wukong.server;

import com.cpgame.wukong.core.*;
import com.cpgame.wukong.redis.*;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.*;

/** 运行时只做：先 LOSS/WIN，再已有倍率，再读取一次 member。 */
final class RedisRoundStore implements AutoCloseable {
    private final RedisClient redis;private final int gid;private final RoundCodec codec=new RoundCodec(new GameRuleCore());private final GameRuleCore rules=new GameRuleCore();
    private RedisRoundStore(RedisClient redis,int gid){this.redis=redis;this.gid=gid;}
    static RedisRoundStore connect(Properties p)throws IOException{int gid=integer(p,"redis.game-id",2210),db=integer(p,"redis.database",15);if(gid<1||db < 0)throw new IllegalArgumentException("controller requires positive redis.game-id and non-negative database");return new RedisRoundStore(RedisClient.connect(p.getProperty("redis.host","18.234.101.161").trim(),integer(p,"redis.port",8021),p.getProperty("redis.username","").trim(),p.getProperty("redis.password",""),db,Boolean.parseBoolean(p.getProperty("redis.ssl","false")),integer(p,"redis.connect-timeout-ms",5000),integer(p,"redis.socket-timeout-ms",30000)),gid);}
    Claimed claim(SecureRandom random)throws IOException{boolean wantWin=random.nextBoolean();List<Candidate> candidates=new ArrayList<>();for(boolean special:new boolean[]{false,true}){String index=special?RedisKeys.specialIndex(gid):RedisKeys.normalIndex(gid);Object raw=redis.command("ZRANGE",index,"0","-1");if(raw instanceof List<?> list)for(Object x:list){int m=Integer.parseInt(String.valueOf(x));if((m>0)==wantWin&&length(special,m)>0)candidates.add(new Candidate(special,m));}}if(candidates.isEmpty())throw new IllegalStateException("Redis db15 cache has no "+(wantWin?"WIN":"LOSS")+" member; runtime generation forbidden");Candidate c=candidates.get(random.nextInt(candidates.size()));long length=length(c.special,c.multiplier);String key=c.special?RedisKeys.specialList(gid,c.multiplier):RedisKeys.normalList(gid,c.multiplier);Object raw=redis.command("LINDEX",key,Long.toString(random.nextLong(length)));if(raw==null)throw new IllegalStateException("Redis member vanished");String member=raw.toString();if(codec.verifyMultiplier(member)!=c.multiplier)throw new IllegalStateException("Redis multiplier mismatch");CompleteRound round=codec.decode(member);if(rules.isSpecial(round)!=c.special)throw new IllegalStateException("Redis pool kind mismatch");return new Claimed(round,rules.classify(round),c.multiplier);}
    private long length(boolean special,int m)throws IOException{Object n=redis.command("LLEN",special?RedisKeys.specialList(gid,m):RedisKeys.normalList(gid,m));return n instanceof Long l?l:Long.parseLong(String.valueOf(n));}private static int integer(Properties p,String k,int d){return Integer.parseInt(p.getProperty(k,Integer.toString(d)).trim());}public void close()throws IOException{redis.close();}
    private record Candidate(boolean special,int multiplier){}record Claimed(CompleteRound round,CompleteRound.Outcome outcome,int multiplier){}
}
