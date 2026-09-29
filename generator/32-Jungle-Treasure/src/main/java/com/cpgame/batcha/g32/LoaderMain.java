package com.cpgame.batcha.g32;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

public final class LoaderMain {
    private LoaderMain() { }

    public static void main(String[] args) {
        try {
            Path configPath = configPath(args);
            LoaderConfig config = LoaderConfig.load(configPath);
            LoadSummary summary = run(config);
            System.out.printf("生成完成：raw gid=%d，写入完整局=%d，候选=%d，rulesHash=%s%n",
                GameRuleCore.RAW_GAME_ID, summary.written(), summary.candidates(), GameRuleCore.RULES_HASH);
        } catch (Exception error) {
            System.err.println("生成失败：" + error.getMessage());
            error.printStackTrace(System.err);
            System.exit(2);
        }
    }

    public static LoadSummary run(LoaderConfig config) throws Exception {
        SecureRandom random = new SecureRandom();
        CompleteRoundFactory factory = new CompleteRoundFactory(
            config.maximumCascades(), config.maximumSpecialSpins(),
            EmpiricalColumnModel.configured(config.outputLimits().symbolWeights));
        IndependentVerifier verifier = new IndependentVerifier(config.maximumRoundMultiplier(),
            config.maximumCascades(), config.maximumSpecialSpins());
        MemberCodec codec = new MemberCodec();
        Map<String, Integer> processCounts = new HashMap<>();
        int written = 0;
        int candidates = 0;
        try (RedisRoundStore store = new RedisRespRoundStore(config.redisHost(), config.redisPort(),
            config.redisPassword(), config.redisDatabase(), config.connectTimeoutMillis(),
            config.readTimeoutMillis())) {
            RedisRoundWriter writer = new RedisRoundWriter(store, codec, verifier,
                config.maximumMembersPerMultiplier(), config.outputLimits().specialCap);
            while (written < config.totalMembers() && candidates < config.maximumCandidates()) {
                RoundMode requested = config.modes().get(random.nextInt(config.modes().size()));
                CompleteRound round = factory.generate(requested, random, config.betSize(), config.betLevel());
                candidates++;
                if (round.multiplier().compareTo(config.maximumRoundMultiplier()) > 0
                    || round.multiplier().stripTrailingZeros().scale() > 0) continue;
                if (!config.outputLimits().accepts(RedisKeys.special(round.mode()), round.multiplier())) continue;
                verifier.verifyCodecRoundTrip(round, codec);
                String key = RedisKeys.list(round.mode(), round.multiplier());
                int current = processCounts.getOrDefault(key, 0);
                try {
                    RedisRoundWriter.WriteResult result = writer.write(round);
                    if (result.written()) {
                        processCounts.put(key, current + 1);
                        written++;
                        if (written % 20 == 0) System.out.println("LOADER_PROGRESS written=" + written + " candidates=" + candidates);
                    }
                } catch (java.io.IOException redisError) {
                    if (written > 0 && redisProgressStop(redisError)) {
                        System.out.println("[warn] Redis stopped after written=" + written + ": " + redisError.getMessage());
                        break;
                    }
                    throw redisError;
                }
            }
        }
        if (written < config.totalMembers()) {
            System.out.println("[warn] 达到候选上限或 Redis 限制，已写入 " + written + "/" + config.totalMembers());
        }
        return new LoadSummary(written, candidates, Map.copyOf(processCounts));
    }

    static boolean redisProgressStop(Throwable error) {
        String text = error == null ? "" : String.valueOf(error.getMessage());
        if (error != null && error.getCause() != null) text += " " + error.getCause().getMessage();
        return text.contains("OOM") || text.contains("maxmemory") || text.contains("timed out")
                || text.contains("Timed out") || text.contains("MISCONF") || text.contains("Connection reset")
                || text.contains("closed") || text.contains("EXECABORT") || text.contains("Broken pipe")
                || text.contains("已关闭连接") || text.contains("中止了一个已建立");
    }

    static Path configPath(String[] args) {
        if (args.length == 1 && !args[0].startsWith("--") && !args[0].isBlank()) return Path.of(args[0]);
        if (args.length == 1 && args[0].startsWith("--config=") && args[0].length() > 9)
            return Path.of(args[0].substring(9));
        if (args.length == 2 && args[0].equals("--config") && !args[1].isBlank() && !args[1].startsWith("--"))
            return Path.of(args[1]);
        throw new IllegalArgumentException("请使用 配置文件、--config 配置文件 或 --config=配置文件");
    }

    public record LoadSummary(int written, int candidates, Map<String, Integer> processPoolCounts) { }
}
