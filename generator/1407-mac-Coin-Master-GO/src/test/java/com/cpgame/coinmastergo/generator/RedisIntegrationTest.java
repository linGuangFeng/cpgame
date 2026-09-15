package com.cpgame.coinmastergo.generator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.ClientSetInfoConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** 使用独立逻辑库和唯一 game-id 对真实 Redis 做定向验收。 */
@EnabledIfSystemProperty(named = "redis.integration", matches = "true")
class RedisIntegrationTest {
    private static final long ISOLATED_GAME_ID = 1407L;
    @TempDir Path temp;

    @Test
    void realRedisReceivesNaturalNormalAndCompleteSpecialMembersWithPerBucketTrim() throws Exception {
        Path base = Path.of("src/main/resources/generator.properties").toAbsolutePath();
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(base)) { properties.load(reader); }
        int port = Integer.getInteger("redis.port", 6379);
        properties.setProperty("redis.port", Integer.toString(port));
        properties.setProperty("redis.database", "14");
        properties.setProperty("redis.game-id", Long.toString(ISOLATED_GAME_ID));
        properties.setProperty("generation.normal-count", "60");
        properties.setProperty("generation.special-count", "3");
        properties.setProperty("generation.batch-size", "7");
        properties.setProperty("generation.max-members-per-multiplier", "3");
        Path config = temp.resolve("generator.properties");
        try (var writer = Files.newBufferedWriter(config)) { properties.store(writer, "isolated Redis test"); }

        var clientConfig = DefaultJedisClientConfig.builder().database(14)
                .clientSetInfoConfig(ClientSetInfoConfig.DISABLED).build();
        try (JedisPool pool = new JedisPool(new HostAndPort("127.0.0.1", port), clientConfig)) {
            try (Jedis jedis = pool.getResource()) { cleanup(jedis); }
            try {
                RedisDirectLoader.LoadSummary summary = RedisDirectLoader.run(config);
                assertEquals(60, summary.normalCandidates());
                assertEquals(3, summary.specialCandidates());
                assertTrue(summary.normalWritten() > 0);
                assertTrue(summary.specialWritten() > 0);
                assertTrue(summary.batches() > 0);

                try (Jedis jedis = pool.getResource()) {
                    List<String> normalRatios = jedis.zrange(RedisDirectLoader.normalIndex(ISOLATED_GAME_ID), 0, -1);
                    List<String> specialRatios = jedis.zrange(RedisDirectLoader.specialIndex(ISOLATED_GAME_ID), 0, -1);
                    assertFalse(normalRatios.isEmpty());
                    assertFalse(specialRatios.isEmpty());
                    for (String ratio : normalRatios) {
                        assertTrue(jedis.llen(RedisDirectLoader.normalList(ISOLATED_GAME_ID, Integer.parseInt(ratio))) <= 3);
                    }
                    MinimalFactCodec codec = new MinimalFactCodec();
                    RoundResultUtil util = new RoundResultUtil();
                    for (String ratio : specialRatios) {
                        int parsed = Integer.parseInt(ratio);
                        String list = RedisDirectLoader.specialList(ISOLATED_GAME_ID, parsed);
                        assertTrue(jedis.llen(list) <= 3);
                        String member = jedis.lindex(list, -1);
                        RoundResultUtil.RoundAnalysis analysis = util.analyze(codec.rebuild(member));
                        assertTrue(analysis.special());
                        assertEquals(parsed, RedisDirectLoader.exactRatio(analysis.multiplier()));
                    }
                }
            } finally {
                try (Jedis jedis = pool.getResource()) { cleanup(jedis); }
            }
        }
    }

    private void cleanup(Jedis jedis) {
        List<String> keys = new ArrayList<>();
        keys.addAll(jedis.keys("BetLog:0" + ISOLATED_GAME_ID + ":*"));
        keys.addAll(jedis.keys("MaryLog:" + ISOLATED_GAME_ID + ":*"));
        keys.add(RedisDirectLoader.normalIndex(ISOLATED_GAME_ID));
        keys.add(RedisDirectLoader.specialIndex(ISOLATED_GAME_ID));
        jedis.del(keys.toArray(String[]::new));
    }
}
