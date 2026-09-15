package com.cpgame.wukong.verify;

import com.cpgame.wukong.redis.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** 独立只读快照：证明正式 Loader 追加并由左侧自然溢出。 */
public final class RedisNamespaceSnapshot {
    public static void main(String[] args)throws Exception{Properties p=new Properties();try(InputStream in=Files.newInputStream(Path.of(args[0]))){p.load(in);}int gid=Integer.parseInt(p.getProperty("redis.game-id"));int total=0;StringJoiner lists=new StringJoiner(",","[","]");try(RedisClient redis=RedisClient.connect(p.getProperty("redis.host"),Integer.parseInt(p.getProperty("redis.port")),p.getProperty("redis.username",""),p.getProperty("redis.password",""),Integer.parseInt(p.getProperty("redis.database")),Boolean.parseBoolean(p.getProperty("redis.ssl")),5000,30000)){for(boolean special:new boolean[]{false,true}){Object raw=redis.command("ZRANGE",special?RedisKeys.specialIndex(gid):RedisKeys.normalIndex(gid),"0","-1");if(raw instanceof List<?> ratios)for(Object ratio:ratios){int m=Integer.parseInt(String.valueOf(ratio));String key=special?RedisKeys.specialList(gid,m):RedisKeys.normalList(gid,m);int n=Integer.parseInt(String.valueOf(redis.command("LLEN",key)));String first=String.valueOf(redis.command("LINDEX",key,"0")),last=String.valueOf(redis.command("LINDEX",key,"-1"));total+=n;lists.add("{\"pool\":\""+(special?"SPECIAL":"NORMAL")+"\",\"multiplier\":"+m+",\"length\":"+n+",\"firstSha256\":\""+sha(first)+"\",\"lastSha256\":\""+sha(last)+"\"}");}}}System.out.println("{\"sourceGameId\":2210,\"redisGameId\":"+gid+",\"totalMembers\":"+total+",\"lists\":"+lists+"}");}
    private static String sha(String s)throws Exception{byte[] b=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII));return HexFormat.of().formatHex(b);}
}
