package com.cpgame.wukong.server;

import com.cpgame.demo.redis.RedisFloorLookup;

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
    Claimed claim(SecureRandom random) throws IOException {
        boolean wantWin = random.nextBoolean();
        boolean firstSpecial = random.nextBoolean();
        for (boolean special : new boolean[]{firstSpecial, !firstSpecial}) {
            var buckets = RedisFloorLookup.open(redis::command,
                    special ? RedisKeys.specialIndex(gid) : RedisKeys.normalIndex(gid),
                    m -> special ? RedisKeys.specialList(gid, m) : RedisKeys.normalList(gid, m),
                    random, wantWin ? 1 : 0, wantWin ? Integer.MAX_VALUE : 0);
            Integer multiplier;
            while ((multiplier = buckets.next()) != null) {
                long length = length(special, multiplier);
                if (length <= 0) continue;
                String key = special ? RedisKeys.specialList(gid, multiplier) : RedisKeys.normalList(gid, multiplier);
                Object raw = redis.command("LINDEX", key, Long.toString(random.nextLong(length)));
                if (raw == null) continue;
                String member = raw.toString();
                if (codec.verifyMultiplier(member) != multiplier) throw new IllegalStateException("Redis multiplier mismatch");
                CompleteRound round = codec.decode(member);
                if (rules.isSpecial(round) != special) throw new IllegalStateException("Redis pool kind mismatch");
                return new Claimed(round, rules.classify(round), multiplier);
            }
        }
        throw new IllegalStateException("Redis cache has no " + (wantWin ? "WIN" : "LOSS") + " member at or below target");
    }
    private long length(boolean special,int m)throws IOException{Object n=redis.command("LLEN",special?RedisKeys.specialList(gid,m):RedisKeys.normalList(gid,m));return n instanceof Long l?l:Long.parseLong(String.valueOf(n));}private static int integer(Properties p,String k,int d){return Integer.parseInt(p.getProperty(k,Integer.toString(d)).trim());}public void close()throws IOException{redis.close();}
    record Claimed(CompleteRound round,CompleteRound.Outcome outcome,int multiplier){}
}
