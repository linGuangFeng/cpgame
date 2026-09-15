package com.cpgame.replica.beeworkshop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Natural complete-round generator that commits each batch to Redis 192.168.10.3 DB15. */
public final class RedisDirectLoader {
    private RedisDirectLoader() {}

    public static void main(String[] args) {
        try {
            Path config = Path.of(args.length == 0 ? "generator.properties" : args[0]).toAbsolutePath().normalize();
            for (int i = 0; i < args.length; i++) {
                if ("--config".equals(args[i]) && i + 1 < args.length) config = Path.of(args[++i]).toAbsolutePath().normalize();
                else if (args[i].startsWith("--config=")) config = Path.of(args[i].substring(9)).toAbsolutePath().normalize();
                else if ("--no-pause".equals(args[i])) continue;
                else if (args.length == 1 && !args[i].startsWith("--")) config = Path.of(args[i]).toAbsolutePath().normalize();
            }
            LoadSummary summary = run(config);
            System.out.printf("LOAD_COMPLETE redisGameId=%d normal=%d special=%d batches=%d zeroLoss=%d rulesVersion=%s rulesHash=%s%n",
                    summary.redisGameId(), summary.normalMembers(), summary.specialMembers(), summary.batches(),
                    summary.zeroLossMembers(), BeeWorkshopRulesMetadata.VERSION, BeeWorkshopRulesMetadata.HASH);
        } catch (Exception ex) {
            System.err.println("[失败] " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            System.exit(1);
        }
    }

    public static LoadSummary run(Path configFile) throws Exception {
        LoaderConfig config = LoaderConfig.load(configFile);
        RedisKeys.requireGame(config.redisGameId());
        CompleteRoundFactory factory = new CompleteRoundFactory(config.normalWeights());
        CompleteRoundCodec codec = new CompleteRoundCodec();
        ResultUtil util = new ResultUtil(new GameRuleCore());
        SecureRandom random = new SecureRandom();
        List<Member> pending = new ArrayList<>(config.batchSize());
        Counters counters = new Counters();
        try (RedisIo redis = RedisIo.connect(config.host(), config.port(), config.username(), config.password(),
                config.database(), config.ssl(), config.connectTimeoutMs(), config.socketTimeoutMs())) {
            generateNaturally(config, factory, codec, util, random, redis, pending, counters);
            flush(redis, pending, config, counters);
        }
        return new LoadSummary(config.redisGameId(), counters.normalMembers, counters.specialMembers, counters.batches,
                counters.zeroLossMembers);
    }

    private static void generateNaturally(LoaderConfig config, CompleteRoundFactory factory, CompleteRoundCodec codec,
                                          ResultUtil util, SecureRandom random, RedisIo redis, List<Member> pending,
                                          Counters counters) throws Exception {
        Map<Integer, Integer> normalPerRatio = new HashMap<>();
        Map<Integer, Integer> specialPerRatio = new HashMap<>();
        int drawsInEntry = 0;
        boolean specialEntry = false;
        long attempts = 0;
        long attemptLimit = Math.max(100_000L, ((long) config.normalCount() + config.specialCount()) * 10_000L);
        while (counters.normalMembers < config.normalCount() || counters.specialMembers < config.specialCount()) {
            Member selected;
            while (true) {
                if (++attempts > attemptLimit) throw new IllegalStateException("配置范围/权重/容量内无法完成生成目标，已达到候选上限");
                if (drawsInEntry >= config.entrySwitchEvery()) {
                    specialEntry = !specialEntry;
                    drawsInEntry = 0;
                }
                GameRuleCore.CompleteRound round;
                try {
                    round = factory.generate(random, specialEntry);
                } catch (RuntimeException rejected) {
                    counters.skippedRejected++;
                    drawsInEntry++;
                    continue;
                }
                drawsInEntry++;
                boolean special = GameRuleCore.special(round.kind());
                if ((special && counters.specialMembers >= config.specialCount())
                        || (!special && counters.normalMembers >= config.normalCount())) continue;
                int ratio = util.integerMultiplier(round);
                if (special) {
                    if (ratio < config.maryMinWinMultiplier() || ratio > config.maryMaxWinMultiplier()) {
                        counters.skippedOverMaxMultiplierRounds++;
                        continue;
                    }
                } else if (ratio < 0) {
                    continue;
                } else if (ratio < config.normalMinWinMultiplier() || ratio > config.normalMaxWinMultiplier()) {
                    counters.skippedOverMaxMultiplierRounds++;
                    continue;
                }
                Map<Integer, Integer> generatedPerRatio = special ? specialPerRatio : normalPerRatio;
                int memberCap = special ? config.specialMaxMembersPerMultiplier() : config.maxMembersPerMultiplier();
                generatedPerRatio.merge(ratio, 1, Integer::sum);
                String payload = codec.encode(round);
                if (!payload.equals(codec.encode(codec.decode(payload)))) throw new IllegalStateException("codec round trip");
                if (util.integerMultiplier(codec.decode(payload)) != ratio) throw new IllegalStateException("codec multiplier mismatch");
                selected = new Member(special, ratio, payload);
                break;
            }
            pending.add(selected);
            if (selected.special()) counters.specialMembers++;
            else {
                counters.normalMembers++;
                if (selected.ratio() == 0) counters.zeroLossMembers++;
            }
            if (pending.size() >= config.batchSize()) flush(redis, pending, config, counters);
        }
        System.out.printf("NATURAL_CLASSIFICATION_COMPLETE normal=%d special=%d zeroLoss=%d discardedRejected=%d%n",
                counters.normalMembers, counters.specialMembers, counters.zeroLossMembers, counters.skippedRejected);
    }

    private static void flush(RedisIo redis, List<Member> pending, LoaderConfig config, Counters counters) throws IOException {
        if (pending.isEmpty()) return;
        List<String[]> commands = new ArrayList<>(pending.size() * 3 + 2);
        commands.add(new String[]{"MULTI"});
        for (Member member : pending) {
            String index = RedisKeys.index(config.redisGameId(), member.special());
            String list = RedisKeys.list(config.redisGameId(), member.special(), member.ratio());
            commands.add(new String[]{"ZADD", index, Integer.toString(member.ratio()), Integer.toString(member.ratio())});
            commands.add(new String[]{"RPUSH", list, member.payload()});
            int cap = member.special() ? config.specialMaxMembersPerMultiplier() : config.maxMembersPerMultiplier();
            commands.add(new String[]{"LTRIM", list, "-" + cap, "-1"});
        }
        commands.add(new String[]{"EXEC"});
        List<Object> replies = redis.pipeline(commands);
        Object exec = replies.get(replies.size() - 1);
        if (!(exec instanceof List<?> values) || values.size() != commands.size() - 2) {
            throw new IOException("Redis EXEC response count mismatch");
        }
        counters.batches++;
        counters.loaded += pending.size();
        System.out.printf("BATCH_COMMITTED batch=%d members=%d loaded=%d%n", counters.batches, pending.size(), counters.loaded);
        pending.clear();
    }

    static boolean tryReserve(Map<Integer, Integer> generatedPerRatio, int ratio, int cap) {
        int current = generatedPerRatio.getOrDefault(ratio, 0);
        if (current >= cap) return false;
        generatedPerRatio.put(ratio, current + 1);
        return true;
    }

    public record LoadSummary(long redisGameId, int normalMembers, int specialMembers, int batches, int zeroLossMembers) {}
    private record Member(boolean special, int ratio, String payload) {}
    private static final class Counters {
        int batches, loaded, normalMembers, specialMembers, zeroLossMembers, skippedRejected, skippedCappedRounds,
                skippedOverMaxMultiplierRounds;
    }

    record LoaderConfig(String host, int port, String username, String password, int database, boolean ssl,
                        int connectTimeoutMs, int socketTimeoutMs, long redisGameId, int normalCount,
                        int specialCount, int batchSize, int maxConsecutiveWins, int maxMarySpins,
                        int maxMembersPerMultiplier, int specialMaxMembersPerMultiplier,
                        int normalMinWinMultiplier, int normalMaxWinMultiplier,
                        int maryMinWinMultiplier, int maryMaxWinMultiplier, int entrySwitchEvery,
                        int[] normalWeights, int[] maryWeights) {
        static LoaderConfig load(Path file) throws IOException {
            if (!Files.isRegularFile(file)) throw new IllegalArgumentException("config file not found: " + file);
            Properties p = new Properties();
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(reader); LoaderLimits.checkKeys(p); }
            if (p.stringPropertyNames().stream().anyMatch(k -> k.toLowerCase(Locale.ROOT).contains("seed")))
                throw new IllegalArgumentException("Formal loader does not accept a seed");
            int[] normalWeights = new int[BeeWorkshopBoardGenerator.SYMBOLS];
            int[] maryWeights = new int[BeeWorkshopBoardGenerator.SYMBOLS];
            for (int symbol = 1; symbol <= BeeWorkshopBoardGenerator.SYMBOLS; symbol++) {
                normalWeights[symbol - 1] = integer(p, "generation.symbol." + symbol + ".normal-weight");
                maryWeights[symbol - 1] = integer(p, "generation.symbol." + symbol + ".mary-weight");
            }
            LoaderConfig c = new LoaderConfig(
                    required(p, "redis.host"), integer(p, "redis.port"),
                    p.getProperty("redis.username", "").trim(), p.getProperty("redis.password", ""),
                    integer(p, "redis.database"), bool(p, "redis.ssl"),
                    integer(p, "redis.connect-timeout-ms"), integer(p, "redis.socket-timeout-ms"),
                    longValue(p, "redis.game-id"), integer(p, "generation.normal-count"),
                    integer(p, "generation.special-count"), integer(p, "generation.batch-size"),
                    integer(p, "generation.max-consecutive-wins"), integer(p, "generation.max-mary-spins"),
                    integer(p, "generation.max-members-per-multiplier"),
                    integer(p, "generation.special-max-members-per-multiplier"),
                    integer(p, "generation.normal-min-win-multiplier"), integer(p, "generation.normal-max-win-multiplier"),
                    integer(p, "generation.mary-min-win-multiplier"), integer(p, "generation.mary-max-win-multiplier"),
                    integer(p, "generation.entry-switch-every"),
                    normalWeights, maryWeights);
            List<String> requiredKeys = new ArrayList<>(List.of("redis.host", "redis.port", "redis.username", "redis.password", "redis.database",
                    "redis.ssl", "redis.connect-timeout-ms", "redis.socket-timeout-ms", "redis.game-id",
                    "generation.normal-count", "generation.special-count", "generation.batch-size",
                    "generation.max-consecutive-wins", "generation.max-mary-spins",
                    "generation.max-members-per-multiplier", "generation.special-max-members-per-multiplier",
                    "generation.normal-min-win-multiplier",
                    "generation.normal-max-win-multiplier", "generation.mary-min-win-multiplier",
                    "generation.mary-max-win-multiplier", "generation.entry-switch-every"));
            for (int symbol = 1; symbol <= BeeWorkshopBoardGenerator.SYMBOLS; symbol++) {
                requiredKeys.add("generation.symbol." + symbol + ".normal-weight");
                requiredKeys.add("generation.symbol." + symbol + ".mary-weight");
            }
            for (String key : requiredKeys) {
                if (!p.containsKey(key) && !"redis.username".equals(key) && !"redis.password".equals(key))
                    throw new IllegalArgumentException("missing config: " + key);
            }
            if (c.host.isBlank() || c.port < 1 || c.port > 65535 || c.database < 0 || c.redisGameId <= 0
                    || c.normalCount < 0 || c.specialCount < 0 || c.batchSize < 1 || c.maxMembersPerMultiplier < 1
                    || c.specialMaxMembersPerMultiplier < 1
                    || c.normalMinWinMultiplier < 0 || c.maryMinWinMultiplier < 0
                    || c.normalMaxWinMultiplier < c.normalMinWinMultiplier || c.maryMaxWinMultiplier < c.maryMinWinMultiplier
                    || c.entrySwitchEvery < 1 || !validWeights(c.normalWeights) || !validWeights(c.maryWeights)) {
                throw new IllegalArgumentException("invalid generator.properties values");
            }
            return c;
        }
        private static boolean validWeights(int[] values) {
            if (values == null || values.length != BeeWorkshopBoardGenerator.SYMBOLS) return false;
            long total = 0;
            for (int value : values) {
                if (value < 0) return false;
                total += value;
            }
            return total > 0;
        }
        private static String required(Properties p, String key) {
            String value = p.getProperty(key);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("missing config: " + key);
            return value.trim();
        }
        private static int integer(Properties p, String key) { return Integer.parseInt(required(p, key)); }
        private static long longValue(Properties p, String key) { return Long.parseLong(required(p, key)); }
        private static boolean bool(Properties p, String key) { return Boolean.parseBoolean(required(p, key)); }
    }
}
