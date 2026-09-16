package com.cpgame.sharpshooter.loader;

import com.cpgame.sharpshooter.core.*;
import redis.clients.jedis.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

public final class RedisLoader {
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Usage: java -jar sharpshooter-loader.jar generator.properties");Properties p=new Properties();try(Reader r=new InputStreamReader(new FileInputStream(args[0]),StandardCharsets.UTF_8)){p.load(r); LoaderLimits.checkKeys(p);}
        String host=req(p,"redis.host"),game=req(p,"redis.game-id");int port=num(p,"redis.port",1,65535),db=num(p,"redis.database",0,15),loss=num(p,"generation.loss-count",0,Integer.MAX_VALUE),win=num(p,"generation.win-count",0,Integer.MAX_VALUE),special=num(p,"generation.special-count",0,Integer.MAX_VALUE),cap=num(p,"generation.max-members-per-multiplier",1,1000000);
RedisKeys.prefix(game);
        DefaultJedisClientConfig.Builder cfg=DefaultJedisClientConfig.builder().database(db).connectionTimeoutMillis(num(p,"redis.connect-timeout-ms",1,120000)).socketTimeoutMillis(num(p,"redis.socket-timeout-ms",1,120000)).ssl(Boolean.parseBoolean(p.getProperty("redis.ssl","false")));String user=p.getProperty("redis.username","").trim(),pass=p.getProperty("redis.password","").trim();if(!user.isEmpty())cfg.user(user);if(!pass.isEmpty())cfg.password(pass);
        GameRuleCore rules=new GameRuleCore();RoundGenerator generator=new RoundGenerator(new SecureRandom(),rules);ResultUtil util=new ResultUtil(rules);RoundCodec codec=new RoundCodec();Map<String,Integer> counts=new TreeMap<>();
        LoaderLimits limits = new LoaderLimits(p);
        loss = limits.lossTarget(loss);
        long[] attempts = {0};
        try(Jedis jedis=new Jedis(host,port,cfg.build())){jedis.connect();for(int i=0;i<loss;i++)if (!write(jedis,game,generator.ordinary(false),util,codec, cap, counts, limits, attempts)) i--;for(int i=0;i<win;i++)if (!write(jedis,game,generator.ordinary(true),util,codec, cap, counts, limits, attempts)) i--;for(int i=0;i<special;i++)if (!write(jedis,game,generator.freeSpins(),util,codec, cap, counts, limits, attempts)) i--;System.out.printf(Locale.ROOT,"LOAD_COMPLETE complete rounds loss=%d win=%d special=%d rulesHash=%s buckets=%s%n",loss,win,special,GameRuleCore.RULES_HASH,counts);}
    }
    private static boolean write(Jedis jedis,String game,CompleteRound round,ResultUtil util,RoundCodec codec,int cap,Map<String,Integer> counts,LoaderLimits limits,long[] attempts){if (++attempts[0] > 10_000) throw new IllegalStateException("Configured range or weights cannot satisfy requested category after 10000 rejected candidates");ResultUtil.Analysis a=util.analyze(round);boolean specialPool = RedisKeys.index(game, a.outcome()).startsWith("MaryKeyList_");
        if (!limits.acceptsHundredths(specialPool, a.integerMultiplier())) return false;
        String member=codec.encode(round);if(!member.equals(codec.encode(codec.decode(member))))throw new IllegalStateException("codec roundtrip");if(!member.chars().allMatch(c->c>=32&&c<=126))throw new IllegalStateException("member is not ASCII");String bucket=RedisKeys.list(game,a.outcome(),a.integerMultiplier());String index=RedisKeys.index(game,a.outcome());String ratio=Integer.toString(a.integerMultiplier());Transaction tx=jedis.multi();tx.rpush(bucket,member);tx.ltrim(bucket,-(specialPool?limits.specialCap:cap),-1);tx.zadd(index,(double)a.integerMultiplier(),ratio);tx.exec();counts.merge(bucket,1,Integer::sum);attempts[0]=0; return true;}
    private static String req(Properties p,String key){String v=p.getProperty(key);if(v==null||v.isBlank())throw new IllegalArgumentException("missing "+key);return v.trim();}
    private static int num(Properties p,String key,int min,int max){int v=Integer.parseInt(req(p,key));if(v<min||v>max)throw new IllegalArgumentException(key+" out of range");return v;}
}
