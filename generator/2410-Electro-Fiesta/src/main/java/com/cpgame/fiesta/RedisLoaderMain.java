package com.cpgame.fiesta;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.DefaultJedisClientConfig;
import java.io.*;
import java.security.SecureRandom;
import java.util.*;

public final class RedisLoaderMain {
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("用法: java -jar redis-loader.jar generator.properties");
        Properties p=new Properties();try(Reader r=new InputStreamReader(new FileInputStream(args[0]),java.nio.charset.StandardCharsets.UTF_8)){p.load(r); LoaderLimits.checkKeys(p);}
        String host=req(p,"redis.host");int port=num(p,"redis.port",1,65535),db=num(p,"redis.database",0,15);String game=req(p,"redis.game-id");
        int connectTimeout=num(p,"redis.connect-timeout-ms",1,120000),socketTimeout=num(p,"redis.socket-timeout-ms",1,120000);boolean ssl=bool(p,"redis.ssl");String username=p.getProperty("redis.username","").trim(),password=p.getProperty("redis.password","").trim();
        int normal=num(p,"generation.normal-count",0,Integer.MAX_VALUE),special=num(p,"generation.special-count",0,Integer.MAX_VALUE),batch=num(p,"generation.batch-size",1,1000000),cap=num(p,"generation.max-members-per-multiplier",1,1000000),maxChain=num(p,"generation.max-consecutive-wins",1,1000),normalMax=num(p,"generation.normal-max-total-multiplier",1,20000),specialMax=num(p,"generation.special-max-total-multiplier",1,20000),modeSteps=num(p,"generation.mode.MULTIPLIER_STICKY.max-spins",1,30),respinWeight=num(p,"generation.mode.RESPIN_UNTIL_WIN.weight",1,1000000),multiplierWeight=num(p,"generation.mode.MULTIPLIER_STICKY.weight",1,1000000);
        LoaderLimits limits=new LoaderLimits(p); long attempts=0;
        SecureRandom secure=new SecureRandom();GameRuleCore rules=new GameRuleCore();ResultUtil util=new ResultUtil(rules);RoundCodec codec=new RoundCodec();CandidateFactory factory=new CandidateFactory(secure,rules,p);Map<String,Integer> processCounts=new HashMap<>();
        DefaultJedisClientConfig.Builder client=DefaultJedisClientConfig.builder().connectionTimeoutMillis(connectTimeout).socketTimeoutMillis(socketTimeout).database(db).ssl(ssl);if(!username.isEmpty())client.user(username);if(!password.isEmpty())client.password(password);
        try(Jedis jedis=new Jedis(host,port,client.build())){jedis.connect();
            int acceptedNormal=0,acceptedSpecial=0;
            try (BatchWriter writer=new BatchWriter(jedis,batch)) {
            while(acceptedNormal<normal){LoaderLimits.checkAttempts(++attempts,(long)normal+special);GameRound round=factory.ordinary(secure.nextInt(437)>=426);ResultUtil.Analysis a=util.analyze(round);if(!limits.accepts(false,a.integerMultiplier())||a.integerMultiplier()>normalMax)continue;write(writer,game,round,a,codec,cap,processCounts);acceptedNormal++;}
            while(acceptedSpecial<special){LoaderLimits.checkAttempts(++attempts,(long)normal+special);GameRound round=chooseRespin(secure,respinWeight,multiplierWeight)?factory.respinRound(maxChain+1):factory.multiplierRound(Math.min(modeSteps,maxChain+1));ResultUtil.Analysis a=util.analyze(round);if(!limits.accepts(true,a.integerMultiplier())||a.integerMultiplier()>specialMax)continue;write(writer,game,round,a,codec,limits.specialCap,processCounts);acceptedSpecial++;}
            writer.flush();
            System.out.printf(Locale.ROOT,"完成：普通 %d，特殊 %d，批次 %d，规则 %s，本进程桶计数 %s%n",acceptedNormal,acceptedSpecial,batch,GameRuleCore.RULES_HASH,processCounts);
            }
        }
    }
    static boolean chooseRespin(SecureRandom secure,int respinWeight,int multiplierWeight){return secure.nextInt(Math.addExact(respinWeight,multiplierWeight))<respinWeight;}
    private static boolean write(BatchWriter writer,String game,GameRound round,ResultUtil.Analysis a,RoundCodec codec,int cap,Map<String,Integer> counts){String key=RedisKeys.list(game,a.outcome(),a.integerMultiplier());int used=cap;int n=counts.getOrDefault(key,0);String member=codec.encode(round);GameRound decoded=codec.decode(member);ResultUtil.Analysis restored=new ResultUtil(new GameRuleCore()).analyze(decoded);if(!a.equals(restored)||!codec.encode(decoded).equals(member))throw new IllegalStateException("codec roundtrip");String index=RedisKeys.index(game,a.outcome());String ratio=Integer.toString(a.integerMultiplier());Transaction tx=writer.transaction();tx.rpush(key,member);tx.ltrim(key,-used,-1);tx.zadd(index,(double)a.integerMultiplier(),ratio);writer.member();counts.put(key,n+1);return true;}
    private static final class BatchWriter implements AutoCloseable {
        private final Jedis jedis; private final int size; private int pending; private Transaction tx;
        BatchWriter(Jedis jedis,int size){this.jedis=jedis;this.size=size;}
        Transaction transaction(){if(tx==null)tx=jedis.multi();return tx;}
        void member(){if(++pending>=size)flush();}
        void flush(){if(tx!=null){tx.exec();tx.close();tx=null;pending=0;}}
        public void close(){if(tx!=null){tx.discard();tx.close();tx=null;}}
    }
    private static String req(Properties p,String k){String v=p.getProperty(k);if(v==null||v.isBlank())throw new IllegalArgumentException("缺少 "+k);return v.trim();}
    private static int num(Properties p,String k,int min,int max){int v=Integer.parseInt(req(p,k));if(v<min||v>max)throw new IllegalArgumentException(k+" out of range");return v;}
    private static boolean bool(Properties p,String k){String v=req(p,k);if(!v.equalsIgnoreCase("true")&&!v.equalsIgnoreCase("false"))throw new IllegalArgumentException(k+" must be true or false");return Boolean.parseBoolean(v);}
}
