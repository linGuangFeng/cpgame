package com.cpgame.wukong.verify;

import com.cpgame.wukong.redis.*;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;

/** 仅供受控空缓存回归；只删除 db15 中当前 gameId 的索引和其列出的 list。 */
public final class RedisCurrentGameClear {
    public static void main(String[] args)throws Exception{if(args.length<2||!"--confirm-namespace".equals(args[1]))throw new IllegalArgumentException("explicit --confirm-namespace required");Properties p=new Properties();try(InputStream in=Files.newInputStream(Path.of(args[0]))){p.load(in);}int gid=Integer.parseInt(p.getProperty("redis.game-id"));if(gid<1||Integer.parseInt(p.getProperty("redis.database"))!=15)throw new IllegalArgumentException("scope must be a positive redis.game-id in db15");try(RedisClient redis=RedisClient.connect(p.getProperty("redis.host"),Integer.parseInt(p.getProperty("redis.port")),p.getProperty("redis.username",""),p.getProperty("redis.password",""),15,Boolean.parseBoolean(p.getProperty("redis.ssl")),5000,30000)){int deleted=0;for(boolean special:new boolean[]{false,true}){String index=special?RedisKeys.specialIndex(gid):RedisKeys.normalIndex(gid);Object raw=redis.command("ZRANGE",index,"0","-1");if(raw instanceof List<?> values)for(Object x:values){int m=Integer.parseInt(String.valueOf(x));redis.command("DEL",special?RedisKeys.specialList(gid,m):RedisKeys.normalList(gid,m));deleted++;}redis.command("DEL",index);}System.out.println("MIGRATION_CLEAR_COMPLETE sourceGameId=2210 redisGameId="+gid+" db=15 lists="+deleted);}}
}
