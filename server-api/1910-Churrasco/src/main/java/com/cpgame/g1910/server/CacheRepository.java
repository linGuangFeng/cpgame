package com.cpgame.g1910.server;

import com.cpgame.g1910.core.*;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** Reads only pre-generated complete-round members from Redis and rotates each consumed member. */
final class CacheRepository {
    enum Pool { LOSS, WIN, SMALL, FREE }
    private final ServerConfig config;private final SecureRandom random=new SecureRandom();CacheRepository(ServerConfig config){this.config=config;}
    GameRuleCore.CompleteRound take(Pool pool){
        boolean special=pool==Pool.FREE;String index=String.format(special?"MaryKeyList_%09d":"PerKeyList_%09d",config.gameId());
        try(var redis=config.openRedis()){
            List<Integer> units=new ArrayList<>();for(String raw:redis.zrange(index,0,-1)){int value=Integer.parseInt(raw);if(special||(pool==Pool.WIN?value>0:value==0))units.add(value);}
            for(int attempt=0;attempt<Math.max(200,units.size()*30)&&!units.isEmpty();attempt++){
                int value=units.get(random.nextInt(units.size()));String key=special?String.format("MaryLog:%09d:%06d",config.gameId(),value):String.format("BetLog:0%08d:%06d",config.gameId(),value);
                long n=redis.llen(key);if(n<=0)continue;String fact=redis.lindex(key,random.nextInt((int)Math.min(n,Integer.MAX_VALUE)));if(fact==null)continue;var round=MinimalRoundFactCodec.decode(fact);var evaluation=ResultUtil.calculate(fact);
                if(evaluation.totalUnits()!=value)throw new IllegalStateException("Redis score/member mismatch");
                boolean ok=switch(pool){case LOSS->round.mode()==GameRuleCore.Mode.ORDINARY&&value==0;case WIN->round.mode()==GameRuleCore.Mode.ORDINARY&&value>0;case SMALL->round.mode()==GameRuleCore.Mode.SMALL_GAME_1;case FREE->round.mode()==GameRuleCore.Mode.FREE_REWARD;};
                if(ok)return round;
            }
        }
        throw new IllegalStateException("required Redis complete-round pool is empty: "+pool);
    }
}
