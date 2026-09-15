package com.hd.cpgame.magicscroll2.loader;

import com.hd.cpgame.magicscroll2.core.GameConstants;
import com.hd.cpgame.magicscroll2.core.GeneratedRound;
import com.hd.cpgame.magicscroll2.core.RedisMemberCodec;
import com.hd.cpgame.magicscroll2.core.ResultUtil;
import com.hd.cpgame.magicscroll2.core.RoundMode;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RedisLoaderTest {
    @Test
    public void formalConfigurationUsesPositiveCurrentGameWeightsAndRejectsForbiddenSwitches() throws Exception {
        GeneratorConfig config = GeneratorConfig.load(new File("src/main/distribution/generator.properties"));
        assertEquals(2001007L, config.redisGameId);
        assertTrue(config.lossCount > 0 && config.lossCount <= GeneratorConfig.MAX_GENERATION_TARGET);
        assertTrue(config.winCount > 0 && config.winCount <= GeneratorConfig.MAX_GENERATION_TARGET);
        assertTrue(config.specialCount > 0 && config.specialCount <= GeneratorConfig.MAX_GENERATION_TARGET);
        assertEquals(300, config.maxMembersPerMultiplier);
        int[] weights = config.symbolWeights.copyWeights();
        for (int weight : weights) assertTrue(weight > 0);
        assertEquals(30587, config.symbolWeights.getEmptyMaterialWeight());
        assertEquals(34776, config.symbolWeights.getDirtMaterialWeight());
        assertTrue("当前游戏权重不能退化为均匀随机", weights[0] != weights[weights.length - 1]);

        Properties base = productionProperties();
        expectRejected(base, "seed", "2001007");
        expectRejected(base, "output.file", "result.jsonl");
        expectRejected(base, "redis.enabled", "false");
        expectRejected(base, "write-enabled", "false");
        expectRejected(base, "generation.symbol.3-weight", "0");
        expectRejected(base, "generation.loss-count", "2147483648");
    }

    @Test
    public void isolatedRedisWritesLossZeroAndAsciiMembers() throws Exception {
        try (IsolatedRedisServer redis = new IsolatedRedisServer()) {
            Properties properties = productionProperties();
            properties.setProperty("redis.host", "127.0.0.1");
            properties.setProperty("redis.port", Integer.toString(redis.port()));
            properties.setProperty("redis.database", "0");
            properties.setProperty("generation.loss-count", "6");
            properties.setProperty("generation.win-count", "4");
            properties.setProperty("generation.special-count", "4");
            properties.setProperty("generation.batch-size", "4");
            properties.setProperty("generation.max-members-per-multiplier", "8");
            File configFile = write(properties);
            GeneratorConfig config = GeneratorConfig.load(configFile);
            RedisLoader.LoadSummary summary = new RedisLoader().run(configFile);

            assertEquals(6, summary.lossMembers);
            assertEquals(4, summary.winMembers);
            assertEquals(4, summary.specialMembers);
            assertTrue(redis.zsets.containsKey("PerKeyList_002001007"));
            assertTrue(redis.zsets.containsKey("MaryKeyList_002001007"));
            assertTrue("0 倍必须写入未中奖池", redis.lists.containsKey("BetLog:002001007:000000"));
            assertEquals(6, redis.lists.get("BetLog:002001007:000000").size());

            RedisMemberCodec codec = new RedisMemberCodec(new ResultUtil(), config.generationPolicy);
            int asciiMembers = 0;
            for (Map.Entry<String, List<String>> entry : redis.lists.entrySet()) {
                for (String payload : entry.getValue()) {
                    assertFalse(payload.startsWith("{"));
                    assertFalse(payload.startsWith("["));
                    assertTrue(payload.startsWith(GameConstants.REDIS_MEMBER_PREFIX + ";"));
                    GeneratedRound round = codec.decode(payload, new BigDecimal("0.40"));
                    ResultUtil.RoundAnalysis analysis =
                            new ResultUtil().analyzeCompleteRound(round, config.generationPolicy);
                    boolean specialKey = entry.getKey().startsWith("MaryLog:");
                    assertEquals(specialKey, analysis.isSpecial());
                    if (entry.getKey().endsWith(":000000")) {
                        assertEquals(RoundMode.LOSS, analysis.getMode());
                        assertEquals(0, new ResultUtil().integerRatio(round, config.generationPolicy));
                    }
                    asciiMembers++;
                }
            }
            assertEquals(14, asciiMembers);
            redis.assertHealthy();
        }
    }

    private static Properties productionProperties() throws Exception {
        Properties properties = new Properties();
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream("src/main/distribution/generator.properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static File write(Properties properties) throws Exception {
        File directory = new File("target/test-config");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建测试配置目录");
        }
        File file = File.createTempFile("generator-", ".properties", directory);
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            properties.store(writer, "test only");
        }
        return file;
    }

    private static void expectRejected(Properties source, String key, String value) throws Exception {
        Properties copy = new Properties();
        copy.putAll(source);
        copy.setProperty(key, value);
        try {
            GeneratorConfig.load(write(copy));
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("配置应被拒绝: " + key + "=" + value);
    }
}
