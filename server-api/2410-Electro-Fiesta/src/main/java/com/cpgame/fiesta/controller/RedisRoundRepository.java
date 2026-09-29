package com.cpgame.fiesta.controller;

import com.cpgame.demo.redis.RedisFloorLookup;

import com.cpgame.fiesta.*;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.DefaultJedisClientConfig;
import java.security.SecureRandom;
import java.util.*;

final class RedisRoundRepository implements AutoCloseable {
    private final Jedis jedis; private final String gameId; private final SecureRandom random=new SecureRandom(); private final RoundCodec codec=new RoundCodec(); private final GameRuleCore rules=new GameRuleCore();
    RedisRoundRepository(Properties p){
        var config=DefaultJedisClientConfig.builder()
                .database(Integer.parseInt(p.getProperty("redis.database")))
                .ssl(Boolean.parseBoolean(p.getProperty("redis.ssl","false")))
                .connectionTimeoutMillis(Integer.parseInt(p.getProperty("redis.connect-timeout-ms","30000")))
                .socketTimeoutMillis(Integer.parseInt(p.getProperty("redis.socket-timeout-ms","30000")));
        String user=p.getProperty("redis.username","").trim(),password=p.getProperty("redis.password","").trim();
        if(!user.isEmpty())config.user(user);if(!password.isEmpty())config.password(password);
        jedis=new Jedis(p.getProperty("redis.host"),Integer.parseInt(p.getProperty("redis.port")),config.build());
        jedis.connect();gameId=p.getProperty("redis.game-id");
    }
    synchronized GameRound claim(){
        if(!random.nextBoolean()){
            GameRound loss=codec.decode("EF1:#");
            ResultUtil.Analysis zero=new ResultUtil(rules).analyze(loss);
            if(zero.integerMultiplier()!=0||zero.outcome()!=RoundOutcome.ORDINARY_LOSS)
                throw new IllegalStateException("zero-loss generator produced a non-loss");
            return loss;
        }
        boolean special=random.nextBoolean();
        Integer selected=choosePool(special,true);
        if(selected==null){
            special=!special;
            selected=choosePool(special,true);
        }
        if(selected==null)throw new CacheEmptyException("Redis win index is empty");
        int multiplier=selected;
        String list=special?RedisKeys.maryList(gameId,multiplier):RedisKeys.normalList(gameId,multiplier);
        long len=jedis.llen(list);
        if(len<=0)throw new CacheEmptyException("all selected Redis multiplier buckets are empty");
        String member=jedis.lindex(list, random.nextInt((int)Math.min(len,Integer.MAX_VALUE)));
        if(member==null)throw new CacheEmptyException("all selected Redis multiplier buckets are empty");
        GameRound round=codec.decode(member);
        ResultUtil.Analysis analysis;
        try {analysis=new ResultUtil(rules).analyze(round);}
        catch(IllegalArgumentException invalid){throw new IllegalStateException("REGENERATE_2410_SPECIAL_CACHE: cached round violates repaired rules: "+invalid.getMessage(),invalid);}
        if(analysis.integerMultiplier()!=multiplier||!RedisKeys.allowedInPool(special,analysis.outcome()))
            throw new IllegalStateException("cached member failed multiplier/pool check");
        return round;
    }
    private Integer choosePool(boolean special, boolean win) {
        return RedisFloorLookup.choose(this::floorCommand,
                special ? RedisKeys.maryIndex(gameId) : RedisKeys.normalIndex(gameId),
                m -> special ? RedisKeys.maryList(gameId, m) : RedisKeys.normalList(gameId, m),
                random, win ? 1 : 0, win ? Integer.MAX_VALUE : 0);
    }
    private Object floorCommand(String... args) {
        return switch (args[0]) {
            case "ZREVRANGEBYSCORE" -> jedis.zrevrangeByScore(args[1], args[2], args[3], 0, 1);
            case "LLEN" -> jedis.llen(args[1]);
            default -> throw new IllegalArgumentException("unexpected lookup command");
        };
    }
    @Override public void close(){jedis.close();}
    static final class CacheEmptyException extends RuntimeException{CacheEmptyException(String message){super(message);}}
}
