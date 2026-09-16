package com.cpgame.fiesta.controller;

import com.cpgame.demo.redis.RedisFloorLookup;

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
        Integer selected = choosePool(special, wantWin);
        if (selected == null && wantWin && !special) {
            special = true;
            selected = choosePool(true, true);
        }
        if (selected == null) throw new CacheEmptyException("Redis " + (wantWin ? "win" : "loss") + " index is empty");
        int multiplier = selected;
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
