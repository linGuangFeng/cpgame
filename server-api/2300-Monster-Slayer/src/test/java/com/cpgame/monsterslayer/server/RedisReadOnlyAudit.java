package com.cpgame.monsterslayer.server;
import com.cpgame.monsterslayer.redis.RedisKeyContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
public final class RedisReadOnlyAudit {
    public static void main(String[]args)throws Exception{
        Properties p=new Properties();
        p.setProperty("redis.host","192.168.10.3");p.setProperty("redis.port","6379");
        p.setProperty("redis.database","15");p.setProperty("redis.game-id","2300");
        p.setProperty("redis.connect-timeout-ms","2000");p.setProperty("redis.socket-timeout-ms","2000");
        Map<String,Object> report=new LinkedHashMap<>();report.put("gameId",2300);
        report.put("host","192.168.10.3");report.put("port",6379);report.put("database",15);report.put("readOnly",true);
        try(RedisCommands redis=SocketRedisCommands.connect(p)){
            report.put("ping",redis.command("PING"));
            for(boolean special:new boolean[]{false,true}){
                Object index=redis.command("ZRANGE",special?RedisKeyContract.specialIndex(2300):RedisKeyContract.normalIndex(2300),"0","-1");
                report.put(special?"specialMultiplierBuckets":"normalMultiplierBuckets",index);
            }
            report.put("ordinaryLossMembers",redis.command("LLEN",RedisKeyContract.normalList(2300,0)));
            report.put("status","READ_OK");
        }catch(Exception e){report.put("status","READ_FAILED");report.put("error",e.getClass().getSimpleName()+": "+e.getMessage());}
        report.put("memberConsumption",0);report.put("generatorPreloadPerformed",false);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),report);
        System.out.println(new ObjectMapper().writeValueAsString(report));
    }
}
