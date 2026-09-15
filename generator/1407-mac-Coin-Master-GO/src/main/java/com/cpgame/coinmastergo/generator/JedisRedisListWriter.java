package com.cpgame.coinmastergo.generator;

import redis.clients.jedis.ClientSetInfoConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Transaction;

import java.util.List;

/** 使用 Jedis 把 ZADD、RPUSH、LTRIM 放入同一个 MULTI/EXEC。 */
public final class JedisRedisListWriter implements RedisListWriter {
    private final JedisPool pool;
    private final int specialCap;

    public JedisRedisListWriter(GeneratorConfiguration configuration) {
        specialCap = configuration.outputLimits.specialCap;
        var builder = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(configuration.connectTimeoutMs)
                .socketTimeoutMillis(configuration.socketTimeoutMs)
                .database(configuration.redisDatabase)
                .ssl(configuration.redisSsl)
                .clientSetInfoConfig(ClientSetInfoConfig.DISABLED);
        if (!configuration.redisUsername.isBlank()) builder.user(configuration.redisUsername);
        if (!configuration.redisPassword.isBlank()) builder.password(configuration.redisPassword);
        this.pool = new JedisPool(new HostAndPort(configuration.redisHost, configuration.redisPort), builder.build());
        try (Jedis jedis = pool.getResource()) {
            if (!"PONG".equalsIgnoreCase(jedis.ping())) throw new IllegalStateException("Redis PING 未返回 PONG");
        }
    }

    @Override
    public void appendBatchAtomically(List<RedisEntry> entries, int maxMembersPerMultiplier) {
        if (entries == null || entries.isEmpty()) return;
        if (maxMembersPerMultiplier <= 0) throw new IllegalArgumentException("每倍率容量必须为正数");
        try (Jedis jedis = pool.getResource()) {
            Transaction transaction = jedis.multi();
            for (RedisEntry entry : entries) {
                double score = Double.parseDouble(entry.multiplier());
                transaction.zadd(entry.indexKey(), score, entry.multiplier());
                transaction.rpush(entry.listKey(), entry.member());
                int cap = entry.listKey().startsWith("MaryLog:") ? specialCap : maxMembersPerMultiplier;
                transaction.ltrim(entry.listKey(), -cap, -1);
            }
            List<Object> replies = transaction.exec();
            if (replies == null || replies.size() != entries.size() * 3) {
                throw new IllegalStateException("Redis EXEC 返回命令数不一致");
            }
        }
    }
    @Override public void close() { pool.close(); }
}
