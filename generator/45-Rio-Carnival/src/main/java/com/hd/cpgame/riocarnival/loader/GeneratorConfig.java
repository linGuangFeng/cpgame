package com.hd.cpgame.riocarnival.loader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
final class GeneratorConfig {
    final LoaderLimits outputLimits;
    final String host,username,password;
    final int port,database,connectTimeoutMs,socketTimeoutMs,normalCount,specialCount,lossCount,batchSize,maxMembersPerMultiplier;
    final long redisGameId;
    final boolean ssl;
    private GeneratorConfig(Properties p, boolean enforceProductionEndpoint) {
        outputLimits = new LoaderLimits(p);
        Set<String> allowed=new HashSet<String>(Arrays.asList("redis.host","redis.port","redis.username","redis.password","redis.database","redis.ssl","redis.connect-timeout-ms","redis.socket-timeout-ms","redis.game-id","generation.normal-count","generation.special-count","generation.loss-count","generation.batch-size","generation.max-members-per-multiplier","generation.special-max-members-per-multiplier","generation.normal-min-win-multiplier","generation.normal-max-win-multiplier","generation.special-min-win-multiplier","generation.special-max-win-multiplier"));
        for(String k:p.stringPropertyNames())if(!allowed.contains(k))throw new IllegalArgumentException("Unsupported production configuration: "+k);
        host=p.getProperty("redis.host");port=n(p,"redis.port",1,65535);database=n(p,"redis.database",0,15);redisGameId=n(p,"redis.game-id",1,99999999);
        if(host==null||host.trim().isEmpty())throw new IllegalArgumentException("Rio45 requires 192.168.10.3:6379 DB15");
        username=p.getProperty("redis.username","");password=p.getProperty("redis.password","");ssl=Boolean.parseBoolean(p.getProperty("redis.ssl","false"));
        connectTimeoutMs=n(p,"redis.connect-timeout-ms",1,30000);socketTimeoutMs=n(p,"redis.socket-timeout-ms",1,120000);
        normalCount=n(p,"generation.normal-count",0,Integer.MAX_VALUE);specialCount=n(p,"generation.special-count",0,Integer.MAX_VALUE);lossCount=n(p,"generation.loss-count",0,Integer.MAX_VALUE);
        batchSize=n(p,"generation.batch-size",1,10000);maxMembersPerMultiplier=n(p,"generation.max-members-per-multiplier",1,1000000);
    }
    static GeneratorConfig load(Path file)throws IOException {
        Properties p=read(file);return new GeneratorConfig(p,true);
    }
    static GeneratorConfig loadForTest(Path file)throws IOException { return new GeneratorConfig(read(file),false); }
    private static Properties read(Path file)throws IOException {Properties p=new Properties();try(Reader in=Files.newBufferedReader(file,StandardCharsets.UTF_8)){p.load(in); LoaderLimits.checkKeys(p);}return p;}
    private static int n(Properties p,String key,int min,int max){int n=Integer.parseInt(p.getProperty(key));if(n<min||n>max)throw new IllegalArgumentException(key+" out of range");return n;}
}
