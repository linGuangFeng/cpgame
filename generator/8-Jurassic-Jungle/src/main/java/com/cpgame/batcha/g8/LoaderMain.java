package com.cpgame.batcha.g8;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Formal raw-gid-8 Redis Loader. It always creates a fresh SecureRandom and accepts no seed. */
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
        try (RedisRoundStore store = new RedisRespRoundStore(
                config.redisHost(), config.redisPort(),
                config.outputLimits().username, config.redisPassword(), config.outputLimits().ssl,
                config.redisDatabase(), config.connectTimeoutMillis(), config.readTimeoutMillis())) {
            return run(config, store);
        }
    }

    /** Testable entry: commits verified members through store.writeBatch in configured batch sizes. */
    public static LoadSummary run(LoaderConfig config, RedisRoundStore store) throws Exception {
        SecureRandom random = new SecureRandom();
        CompleteRoundFactory factory = new CompleteRoundFactory(
            config.maximumSteps(),
            EmpiricalColumnModel.configured(config.outputLimits().symbolWeights));
        IndependentVerifier verifier = new IndependentVerifier(config.maximumRoundMultiplier(), config.maximumSteps());
        MemberCodec codec = new MemberCodec();
        Map<String, Integer> processCounts = new HashMap<>();
        int written = 0;
        int candidates = 0;
        int normalWritten = 0;
        int specialWritten = 0;
        int normalTarget = config.outputLimits().normalCount;
        int specialTarget = config.outputLimits().specialCount;
        boolean splitQuota = normalTarget >= 0;
        int batchSize = config.outputLimits().batchSize;
        List<RedisRoundStore.PendingMember> pending = new ArrayList<>(batchSize);

        while (written < config.totalMembers() && candidates < config.maximumCandidates()) {
            List<RoundMode> modes = eligibleModes(config, splitQuota, normalWritten, specialWritten,
                normalTarget, specialTarget);
            if (modes.isEmpty()) break;
            RoundMode requested = modes.get(random.nextInt(modes.size()));
            CompleteRound round = factory.generate(requested, random, config.betSize(), config.betLevel());
            candidates++;
            if (round.multiplier().compareTo(config.maximumRoundMultiplier()) > 0) continue;
            boolean special = RedisKeys.special(round.mode());
            if (!config.outputLimits().accepts(special, round.unitRatio())) continue;
            if (splitQuota) {
                if (special && specialWritten >= specialTarget) continue;
                if (!special && normalWritten >= normalTarget) continue;
            }
            try {
                verifier.verifyCodecRoundTrip(round, codec);
            } catch (RuntimeException codecError) {
                System.err.println("skip candidate: " + codecError.getMessage());
                continue;
            }
            int cap = special ? config.outputLimits().specialCap : config.maximumMembersPerMultiplier();
            pending.add(new RedisRoundStore.PendingMember(special, round.unitRatio(), codec.encode(round), cap));
            String key = RedisKeys.list(round.mode(), round.unitRatio());
            processCounts.put(key, processCounts.getOrDefault(key, 0) + 1);
            written++;
            if (special) specialWritten++; else normalWritten++;
            if (pending.size() >= batchSize) {
                try {
                    store.writeBatch(List.copyOf(pending));
                    pending.clear();
                } catch (java.io.IOException redisError) {
                    if (written > 0 && redisProgressStop(redisError)) {
                        System.out.println("[warn] Redis stopped after written=" + written + ": " + redisError.getMessage());
                        pending.clear();
                        break;
                    }
                    throw redisError;
                }
            }
            if (written % 50 == 0) {
                System.out.println("LOADER_PROGRESS written=" + written + " candidates=" + candidates
                    + " lastMode=" + round.mode() + " units=" + round.unitRatio()
                    + " pending=" + pending.size());
            }
        }
        if (!pending.isEmpty()) {
            try {
                store.writeBatch(List.copyOf(pending));
                pending.clear();
            } catch (java.io.IOException redisError) {
                if (written > 0 && redisProgressStop(redisError)) {
                    System.out.println("[warn] Redis stopped after written=" + written + ": " + redisError.getMessage());
                    pending.clear();
                } else {
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

    /** Drop modes that the current range/quota can never accept (e.g. LOSS when normal-min > 0). */
    private static List<RoundMode> eligibleModes(LoaderConfig config, boolean splitQuota,
                                                 int normalWritten, int specialWritten,
                                                 int normalTarget, int specialTarget) {
        List<RoundMode> out = new ArrayList<>();
        for (RoundMode mode : config.modes()) {
            boolean special = RedisKeys.special(mode);
            if (splitQuota) {
                if (special && specialWritten >= specialTarget) continue;
                if (!special && normalWritten >= normalTarget) continue;
            }
            if (mode == RoundMode.LOSS && !config.outputLimits().accepts(false, 0)) continue;
            out.add(mode);
        }
        return out;
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
