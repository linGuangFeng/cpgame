package com.cpgame.g1910.server;

import com.cpgame.g1910.core.RedisClient;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

record ServerConfig(Path publishDirectory,String redisHost,int redisPort,String redisUsername,String redisPassword,int redisDatabase,boolean redisSsl,int connectTimeoutMs,int socketTimeoutMs,int gameId,double lossProbability,double smallProbability,double freeProbability){
    static ServerConfig load(Path configPath,Path publishOverride)throws Exception{
        Properties p=new Properties();try(InputStream in=Files.newInputStream(configPath)){p.load(in);}Path base=configPath.toAbsolutePath().normalize().getParent();
        Path publish=publishOverride!=null?publishOverride.toAbsolutePath().normalize():base.resolve(required(p,"publish.directory")).normalize();
        var c=new ServerConfig(publish,required(p,"redis.host"),integer(p,"redis.port"),p.getProperty("redis.username",""),p.getProperty("redis.password",""),integer(p,"redis.database"),Boolean.parseBoolean(required(p,"redis.ssl")),integer(p,"redis.connect-timeout-ms"),integer(p,"redis.socket-timeout-ms"),integer(p,"redis.game-id"),decimal(p,"selection.loss-probability"),decimal(p,"selection.small-probability"),decimal(p,"selection.free-probability"));
        if(!"18.234.101.161".equals(c.redisHost)||c.redisPort!=8021||c.redisDatabase < 0||c.gameId <= 0)throw new IllegalArgumentException("fixed Redis/game contract mismatch");
        if(!Files.isRegularFile(c.publishDirectory.resolve("index.html")))throw new IllegalArgumentException("original publish index.html missing");return c;
    }
    RedisClient openRedis(){return new RedisClient(redisHost,redisPort,redisUsername,redisPassword,redisDatabase,redisSsl,connectTimeoutMs,socketTimeoutMs);}
    private static String required(Properties p,String key){String value=p.getProperty(key);if(value==null||value.isBlank())throw new IllegalArgumentException("missing "+key);return value.strip();}
    private static int integer(Properties p,String key){return Integer.parseInt(required(p,key));}private static double decimal(Properties p,String key){return Double.parseDouble(required(p,key));}
}
