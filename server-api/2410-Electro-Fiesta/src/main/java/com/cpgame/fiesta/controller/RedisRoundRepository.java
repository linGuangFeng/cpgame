package com.cpgame.fiesta.controller;

import com.cpgame.fiesta.*;
import redis.clients.jedis.Jedis;
import java.security.SecureRandom;
import java.util.*;

final class RedisRoundRepository implements AutoCloseable {
    private final Jedis jedis; private final String gameId; private final SecureRandom random=new SecureRandom(); private final RoundCodec codec=new RoundCodec(); private final GameRuleCore rules=new GameRuleCore();
    RedisRoundRepository(Properties p){jedis=new Jedis(p.getProperty("redis.host"),Integer.parseInt(p.getProperty("redis.port")));jedis.connect();String password=p.getProperty("redis.password","").trim();if(!password.isEmpty())jedis.auth(password);jedis.select(Integer.parseInt(p.getProperty("redis.database")));gameId=p.getProperty("redis.game-id");}
    synchronized GameRound claim(){
        boolean wantWin=random.nextBoolean();
        boolean special=wantWin && random.nextBoolean();
        String index=special?RedisKeys.maryIndex(gameId):RedisKeys.normalIndex(gameId);
        List<String> choices=jedis.zrange(index,0,-1);
        if(choices.isEmpty() && wantWin && !special){
            special=true; index=RedisKeys.maryIndex(gameId); choices=jedis.zrange(index,0,-1);
        }
        List<Integer> ratios=new ArrayList<>();
        for(String choice:choices){
            int multiplier=Integer.parseInt(choice);
            if(!wantWin && multiplier!=0) continue;
            if(wantWin && multiplier<=0) continue;
            String list=special?RedisKeys.maryList(gameId,multiplier):RedisKeys.normalList(gameId,multiplier);
            if(jedis.llen(list)>0) ratios.add(multiplier);
        }
        if(ratios.isEmpty())throw new CacheEmptyException("Redis "+(wantWin?"win":"loss")+" index is empty");
        int multiplier=ratios.get(random.nextInt(ratios.size()));
        String list=special?RedisKeys.maryList(gameId,multiplier):RedisKeys.normalList(gameId,multiplier);
        long len=jedis.llen(list);
        if(len<=0)throw new CacheEmptyException("all selected Redis multiplier buckets are empty");
        String member=jedis.lindex(list, random.nextInt((int)Math.min(len,Integer.MAX_VALUE)));
        if(member==null)throw new CacheEmptyException("all selected Redis multiplier buckets are empty");
        GameRound round=codec.decode(member);
        ResultUtil.Analysis analysis=new ResultUtil(rules).analyze(round);
        if(analysis.integerMultiplier()!=multiplier || RedisKeys.special(analysis.outcome())!=special)
            throw new IllegalStateException("cached member failed multiplier/pool check");
        return round;
    }
    @Override public void close(){jedis.close();}
    static final class CacheEmptyException extends RuntimeException{CacheEmptyException(String message){super(message);}}
}
