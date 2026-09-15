package com.cpgame.beachfun.api;

import com.cpgame.beachfun.core.*;
import redis.clients.jedis.Jedis;
import java.security.SecureRandom;
import java.util.*;

final class RedisRoundRepository implements AutoCloseable {
    private final Jedis redis;
    private final SecureRandom random=new SecureRandom();private final MinimalRoundFactCodec codec=new MinimalRoundFactCodec();
    private final long GAME_ID;
    RedisRoundRepository(Properties p){
        GAME_ID=Long.parseLong(p.getProperty("redis.game-id","8001940"));
        if(GAME_ID<=0)throw new IllegalArgumentException("redis.game-id must be positive");
        redis=new Jedis(p.getProperty("redis.host"),Integer.parseInt(p.getProperty("redis.port")),5000,30000);redis.connect();
        String pw=p.getProperty("redis.password","").trim();if(!pw.isEmpty())redis.auth(pw);redis.select(Integer.parseInt(p.getProperty("redis.database")));
    }
    private static void require(Properties p,String key,String expected){if(!expected.equals(p.getProperty(key,expected)))throw new IllegalArgumentException("fixed "+key+" required");}
    synchronized Claimed claim(){
        // Exactly one outcome coin toss per paid start. An empty selected side fails.
        boolean win=random.nextBoolean();
        boolean special=win && random.nextBoolean();
        String index=special?String.format("MaryKeyList_%09d",GAME_ID):String.format("PerKeyList_%09d",GAME_ID);
        List<Integer> available=new ArrayList<>();
        for(String value:redis.zrange(index,0,-1)){
            int multiplier=Integer.parseInt(value);
            if(!win && multiplier!=0) continue;
            if(win && multiplier<=0) continue;
            if(redis.llen(listKey(special,multiplier))>0) available.add(multiplier);
        }
        if(available.isEmpty() && win && !special){
            special=true; index=String.format("MaryKeyList_%09d",GAME_ID);
            for(String value:redis.zrange(index,0,-1)){
                int multiplier=Integer.parseInt(value);
                if(multiplier>0 && redis.llen(listKey(true,multiplier))>0) available.add(multiplier);
            }
        }
        if(available.isEmpty() && win && special){
            special=false; index=String.format("PerKeyList_%09d",GAME_ID);
            for(String value:redis.zrange(index,0,-1)){
                int multiplier=Integer.parseInt(value);
                if(multiplier>0 && redis.llen(listKey(false,multiplier))>0) available.add(multiplier);
            }
        }
        if(available.isEmpty())throw new CacheEmptyException("empty "+(win?"WIN":"LOSS")+" pool");
        int multiplier=available.get(random.nextInt(available.size()));
        long len=redis.llen(listKey(special,multiplier));
        if(len<=0)throw new CacheEmptyException("selected bucket became empty");
        String member=redis.lindex(listKey(special,multiplier), random.nextInt((int)Math.min(len,Integer.MAX_VALUE)));
        if(member==null)throw new CacheEmptyException("selected bucket became empty");
        var round=codec.decode(member);
        String outcome=ResultUtil.outcome(round);
        if(ResultUtil.integerMultiplier(round)!=multiplier)throw new IllegalStateException("bucket/member mismatch");
        return new Claimed(member,round,multiplier,outcome);
    }
    synchronized GameRuleCore.Delivery initialBoard(){
        String member=redis.lindex(listKey(false,0),0);
        if(member==null)throw new CacheEmptyException("empty LOSS initialization pool");
        var round=codec.decode(member);if(round.win()||round.freeFeature())throw new IllegalStateException("invalid idle member");
        return round.deliveries().get(0);
    }
    private String listKey(boolean special,int multiplier){
        return special?String.format("MaryLog:%09d:%06d",GAME_ID,multiplier):String.format("BetLog:0%08d:%06d",GAME_ID,multiplier);
    }
    @Override public void close(){redis.close();}
    record Claimed(String member,GameRuleCore.CompleteRound round,int integerMultiplier,String outcome){}
    static final class CacheEmptyException extends RuntimeException {CacheEmptyException(String m){super(m);}}
}
