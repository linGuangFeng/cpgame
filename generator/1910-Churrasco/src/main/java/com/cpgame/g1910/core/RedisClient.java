package com.cpgame.g1910.core;

import java.util.List;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;

/** Thin Redis adapter; complete pools are replaced with one MULTI/EXEC transaction. */
public final class RedisClient implements AutoCloseable {
    private final Jedis jedis;
    public RedisClient(String host,int port,String username,String password,int database,boolean ssl,int connectTimeout,int socketTimeout){
        var builder=DefaultJedisClientConfig.builder().database(database).ssl(ssl).connectionTimeoutMillis(connectTimeout).socketTimeoutMillis(socketTimeout);
        if(!username.isBlank())builder.user(username);if(!password.isBlank())builder.password(password);
        jedis=new Jedis(new HostAndPort(host,port),builder.build());
    }
    public void ping(){if(!"PONG".equalsIgnoreCase(jedis.ping()))throw new IllegalStateException("Redis ping failed");}
    public String lpop(String key){return jedis.lpop(key);} public String lindex(String key,long index){return jedis.lindex(key,index);} public void rpush(String key,String value){jedis.rpush(key,value);}
    public List<String> zrange(String key,long start,long stop){return jedis.zrange(key,start,stop);} public long llen(String key){return jedis.llen(key);}
    public void replace(String indexKey,List<Bucket>buckets,int maxMembers){
        var transaction=jedis.multi(); // MULTI
        var keys=buckets.stream().map(Bucket::key).toList(); transaction.del(indexKey); if(!keys.isEmpty())transaction.del(keys.toArray(String[]::new));
        for(Bucket bucket:buckets){transaction.zadd(indexKey,bucket.score(),Integer.toString((int)bucket.score())); // ZADD
            if(!bucket.values().isEmpty())transaction.rpush(bucket.key(),bucket.values().toArray(String[]::new)); // RPUSH
            transaction.ltrim(bucket.key(),0,Math.min(maxMembers,bucket.values().size())-1);}// LTRIM
        transaction.exec(); // EXEC
    }
    public void append(String indexKey, List<Bucket> buckets, int cap, int batchSize) {
        if(cap<1||batchSize<1)throw new IllegalArgumentException("invalid cap/batch");
        redis.clients.jedis.Transaction tx=null; int pending=0;
        try {
            for(Bucket bucket:buckets) for(String member:bucket.values()) {
                if(tx==null)tx=jedis.multi();
                tx.zadd(indexKey,bucket.score(),Integer.toString((int)bucket.score()));
                tx.rpush(bucket.key(),member);tx.ltrim(bucket.key(),-cap,-1);
                if(++pending>=batchSize){if(tx.exec()==null)throw new IllegalStateException("Redis EXEC aborted");tx=null;pending=0;}
            }
            if(tx!=null&&tx.exec()==null)throw new IllegalStateException("Redis EXEC aborted");
        } finally {if(tx!=null)tx.close();}
    }
    @Override public void close(){jedis.close();}
    public record Bucket(String key,double score,List<String>values){}
}
