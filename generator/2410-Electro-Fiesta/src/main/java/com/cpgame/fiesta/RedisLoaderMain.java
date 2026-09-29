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
        int batch=num(p,"generation.batch-size",1,1000000),cap=num(p,"generation.max-members-per-multiplier",1,1000000);
        LoaderLimits limits=new LoaderLimits(p);
        RoundCodec codec=new RoundCodec();Map<String,Integer> processCounts=new HashMap<>();
        DefaultJedisClientConfig.Builder client=DefaultJedisClientConfig.builder().connectionTimeoutMillis(connectTimeout).socketTimeoutMillis(socketTimeout).database(db).ssl(ssl);if(!username.isEmpty())client.user(username);if(!password.isEmpty())client.password(password);
        try(Jedis jedis=new Jedis(host,port,client.build())) {
            jedis.connect();
            try (BatchWriter writer=new BatchWriter(jedis,batch)) {
                GenerationSummary summary=generate(p, (round,a,special) -> write(writer,game,round,a,codec,special,
                        special?limits.specialCap:cap,processCounts));
                writer.flush();
                System.out.printf(Locale.ROOT,"完成：普通 %d，特殊 %d，候选 %d，规则 %s，本进程桶计数 %s%n",
                        summary.normal(),summary.special(),summary.attempts(),GameRuleCore.RULES_HASH,processCounts);
            }
        }
    }

    record GenerationSummary(int normal,int special,long attempts) {}
    @FunctionalInterface interface RoundSink { void accept(GameRound round, ResultUtil.Analysis analysis, boolean special); }
    static GenerationSummary generate(Properties p, RoundSink sink) {
        return EnumerationLoader.generate(p,sink);
    }
    static boolean redisProgressStop(Throwable error) {
        String text = error == null ? "" : String.valueOf(error.getMessage());
        if (error != null && error.getCause() != null) text += " " + error.getCause().getMessage();
        return text.contains("OOM") || text.contains("maxmemory") || text.contains("timed out")
                || text.contains("Timed out") || text.contains("MISCONF") || text.contains("Connection reset")
                || text.contains("closed") || text.contains("EXECABORT") || text.contains("Broken pipe")
                || text.contains("已关闭连接") || text.contains("中止了一个已建立");
    }
    static boolean chooseRespin(SecureRandom secure,int respinWeight,int multiplierWeight){return secure.nextInt(Math.addExact(respinWeight,multiplierWeight))<respinWeight;}
    private static boolean write(BatchWriter writer,String game,GameRound round,ResultUtil.Analysis a,RoundCodec codec,boolean special,int cap,Map<String,Integer> counts){
        if(!RedisKeys.allowedInPool(special,a.outcome()))throw new IllegalStateException("pool mismatch");
        String key=RedisKeys.list(game,special,a.integerMultiplier());int used=cap;int n=counts.getOrDefault(key,0);String member=codec.encode(round);GameRound decoded=codec.decode(member);ResultUtil.Analysis restored=new ResultUtil(new GameRuleCore()).analyze(decoded);if(!a.equals(restored)||!codec.encode(decoded).equals(member))throw new IllegalStateException("codec roundtrip");String index=RedisKeys.index(game,special);String ratio=Integer.toString(a.integerMultiplier());Transaction tx=writer.transaction();tx.rpush(key,member);tx.ltrim(key,-used,-1);tx.zadd(index,(double)a.integerMultiplier(),ratio);writer.member();counts.put(key,n+1);return true;}
    private static final class BatchWriter implements AutoCloseable {
        private final Jedis jedis; private final int size; private int pending; private long committed; private Transaction tx;
        BatchWriter(Jedis jedis,int size){this.jedis=jedis;this.size=size;}
        Transaction transaction(){if(tx==null)tx=jedis.multi();return tx;}
        void member(){if(++pending>=size)flush();}
        void flush(){if(tx!=null){tx.exec();tx.close();tx=null;committed+=pending;System.out.printf("BATCH_COMMITTED members=%d loaded=%d%n",pending,committed);pending=0;}}
        public void close(){if(tx!=null){tx.discard();tx.close();tx=null;}}
    }
    private static String req(Properties p,String k){String v=p.getProperty(k);if(v==null||v.isBlank())throw new IllegalArgumentException("缺少 "+k);return v.trim();}
    private static int num(Properties p,String k,int min,int max){int v=Integer.parseInt(req(p,k));if(v<min||v>max)throw new IllegalArgumentException(k+" out of range");return v;}
    private static boolean bool(Properties p,String k){String v=req(p,k);if(!v.equalsIgnoreCase("true")&&!v.equalsIgnoreCase("false"))throw new IllegalArgumentException(k+" must be true or false");return Boolean.parseBoolean(v);}
}
