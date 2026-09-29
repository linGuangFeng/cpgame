package com.cpgame.crazybirds.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 离线执行正式生成链并输出审计 JSON；不连接、不读取也不写入 Redis。 */
public final class GenerationAuditMain {
    private GenerationAuditMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 3) {
            throw new IllegalArgumentException(
                    "用法：java -cp crazybirds-loader.jar " + GenerationAuditMain.class.getName()
                            + " generator.properties [attempts] [output.json]");
        }
        Path configPath = Path.of(args[0]).toAbsolutePath().normalize();
        GeneratorConfig config = GeneratorConfig.load(configPath);
        long attempts = args.length == 2 ? Long.parseLong(args[1])
                : (long) config.batchSize * config.weights.phaseCount();
        RedisLoader.LoadSummary summary = new RedisLoader().audit(config, attempts, new SecureRandom());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", "1.0");
        report.put("generatedAt", OffsetDateTime.now().toString());
        report.put("game", "60-Crazy-Birds");
        report.put("auditMode", "OFFLINE_FORMAL_PATH_NO_REDIS");
        report.put("rulesHash", GameRules.RULES_HASH);
        report.put("cacheMemberFormat", "24_CHAR_BOARD[|24_CHAR_BOARD...]");
        report.put("cacheMemberHeaderLength", 0);
        report.put("cacheMultiplierScale", GameRules.CACHE_MULTIPLIER_SCALE);
        report.put("weightSource", config.weights.source());
        report.put("configuredGenerationCount", config.generationCount);
        report.put("batchSize", config.batchSize);
        report.put("configuredMultiplierRanges", Map.of(
                "normal", Map.of(
                        "minimumCacheIndex", config.normalMinCacheMultiplier,
                        "maximumCacheIndex", config.normalMaxCacheMultiplier),
                "freeSpins", Map.of(
                        "minimumCacheIndex", config.freeMinCacheMultiplier,
                        "maximumCacheIndex", config.freeMaxCacheMultiplier)));
        report.put("attempts", summary.attempts());
        report.put("accepted", summary.accepted());
        report.put("rejected", summary.rejected());
        report.put("batches", summary.batches());
        report.put("uniqueFullRoundFacts", summary.uniqueFacts());
        report.put("maxStepCount", summary.maxDeliveries());
        report.put("modes", summary.modes());
        report.put("rejectionReasons", summary.rejectionReasons());
        report.put("normalMultiplierBucketCount", summary.normalDistribution().size());
        report.put("freeMultiplierBucketCount", summary.freeDistribution().size());
        report.put("normalMultiplierRange", multiplierRange(summary.normalDistribution()));
        report.put("freeMultiplierRange", multiplierRange(summary.freeDistribution()));
        report.put("normalMultiplierDistribution", summary.normalDistribution());
        report.put("freeMultiplierDistribution", summary.freeDistribution());
        report.put("phaseAttempts", summary.phaseAttempts());
        report.put("phaseAccepted", summary.phaseAccepted());
        report.put("redisRetainedCount", null);
        report.put("redisNote", "离线审计未连接 Redis；不得把 accepted 当作实际保留存量");

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        String json = mapper.writeValueAsString(report) + System.lineSeparator();
        if (args.length == 3) {
            Path output = Path.of(args[2]).toAbsolutePath().normalize();
            if (output.getParent() != null) Files.createDirectories(output.getParent());
            Files.writeString(output, json, StandardCharsets.UTF_8);
            System.out.println("审计报告已写入 " + output);
        } else {
            System.out.print(json);
        }
    }

    private static Map<String, Object> multiplierRange(Map<Integer, Long> distribution) {
        Integer minimum = distribution.keySet().stream().min(Integer::compareTo).orElse(null);
        Integer minimumPositive = distribution.keySet().stream().filter(value -> value > 0)
                .min(Integer::compareTo).orElse(null);
        Integer maximum = distribution.keySet().stream().max(Integer::compareTo).orElse(null);
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("minimum", minimum);
        range.put("minimumPositive", minimumPositive);
        range.put("maximum", maximum);
        range.put("zeroCount", distribution.getOrDefault(0, 0L));
        return range;
    }
}
