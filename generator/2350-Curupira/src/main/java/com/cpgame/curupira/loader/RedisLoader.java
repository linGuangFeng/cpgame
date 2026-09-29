package com.cpgame.curupira.loader;

import com.cpgame.curupira.config.EngineConfiguration;
import com.cpgame.curupira.core.RulesContract;
import com.cpgame.curupira.random.SecureRandomSource;
import com.cpgame.curupira.redis.RedisListClient;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/** 使用统一尝试次数与批次权重循环，把规则生成的完整普通局写入 Redis。 */
public final class RedisLoader {
    private RedisLoader() { }

    public static void main(String[] args) throws Exception {
        LoadSummary summary = run(config(args));
        System.out.printf(
                "生成完成 sourceGameId=%d redisGameId=%d attempts=%d accepted=%d rejected=%d batches=%d transactions=%d buckets=%d counts=%s rejectReasons=%s rulesHash=%s%n",
                RulesContract.GAME_ID, summary.redisGameId(), summary.generation().attempts(),
                summary.generation().accepted(), summary.generation().rejected(), summary.generation().batches(),
                summary.transactions(), summary.generation().multiplierFrequency().size(),
                summary.generation().acceptedByKind(), summary.generation().rejectedByReason(), RulesContract.RULES_HASH);
    }

    public static LoadSummary run(Path configPath) throws Exception {
        return run(EngineConfiguration.load(configPath));
    }

    public static LoadSummary run(EngineConfiguration config) throws Exception {
        AtomicLong transactions = new AtomicLong();
        try (RedisListClient redis = new RedisListClient(config)) {
            System.out.printf(
                    "2350 loader start redisGameId=%d attempts=%d batchSize=%d cycle=%d normalRetention=%d maryRetention=%d (no cache wipe)%n",
                    config.redisGameId(), config.generationCount(), config.batchSize(), config.cycleLength(),
                    config.normalRetention(), config.maryRetention());
            GenerationRun.Summary generation = GenerationRun.execute(config, new SecureRandomSource(),
                    (batch, phase, entries) -> {
                        if (!entries.isEmpty()) {
                            redis.appendBatch(entries, config.normalRetention());
                            transactions.incrementAndGet();
                        }
                        if (batch < 10 || (batch + 1) % 100 == 0 || batch + 1 == expectedBatches(config)) {
                            System.out.printf("progress batch=%d phase=%d attemptsInBatch=%d acceptedInBatch=%d%n",
                                    batch + 1, phase, batchAttempts(config, batch), entries.size());
                        }
                    });
            return new LoadSummary(config.redisGameId(), transactions.get(), generation);
        }
    }

    private static int expectedBatches(EngineConfiguration config) {
        return Math.toIntExact((config.generationCount() + config.batchSize() - 1L) / config.batchSize());
    }

    private static int batchAttempts(EngineConfiguration config, int zeroBasedBatch) {
        long start = (long) zeroBasedBatch * config.batchSize();
        return (int) Math.min(config.batchSize(), config.generationCount() - start);
    }

    private static Path config(String[] args) {
        for (String arg : args) if (arg.toLowerCase().contains("seed")) {
            throw new IllegalArgumentException("正式 Loader 禁止 seed 参数");
        }
        if (args.length > 1) throw new IllegalArgumentException("用法：java -jar loader.jar [generator.properties]");
        return Path.of(args.length == 0 ? "generator.properties" : args[0]).toAbsolutePath().normalize();
    }

    public record LoadSummary(int redisGameId, long transactions, GenerationRun.Summary generation) { }
}
