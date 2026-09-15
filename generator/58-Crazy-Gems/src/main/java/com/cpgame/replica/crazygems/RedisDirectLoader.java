package com.cpgame.replica.crazygems;

import com.hd.pg.appapi.business.vo.cpgame.crazygems.CrazyGemsRulesMetadata;
import com.hd.pg.appapi.business.vo.cpgame.crazygems.CrazyGemsBoard;
import com.hd.pg.appapi.business.vo.cpgame.crazygems.CrazyGemsBoardGenerator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Natural complete rounds written straight to Redis. 0x is skipped; the Demo
 * controller builds misses from IndependentLossGenerator using round.loss-probability.
 */
public final class RedisDirectLoader {
    static final int ENTRY_SWITCH_EVERY = 1000;

    private RedisDirectLoader() { }

    public static void main(String[] args) {
        try {
            if (args.length > 1) {
                throw new IllegalArgumentException("usage: java -jar crazygems-redis-loader.jar [generator.properties]");
            }
            Path config = Path.of(args.length == 0 ? "generator.properties" : args[0]).toAbsolutePath().normalize();
            LoadSummary summary = run(config);
            System.out.printf("LOAD_COMPLETE redisGameId=%d normal=%d special=%d batches=%d loss=%d win=%d rulesVersion=%s rulesHash=%s%n",
                    summary.redisGameId(), summary.normalMembers(), summary.specialMembers(), summary.batches(),
                    summary.lossMembers(), summary.winMembers(),
                    CrazyGemsRulesMetadata.VERSION, CrazyGemsRulesMetadata.HASH);
        } catch (Exception ex) {
            System.err.println("[失败] " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            System.exit(1);
        }
    }

    public static LoadSummary run(Path configFile) throws Exception {
        LoaderConfig config = LoaderConfig.load(configFile);
        CompleteRoundFactory factory = new CompleteRoundFactory(config.symbolWeights(), config.rpxWeights());
        SecureRandom random = new SecureRandom();
        List<Member> pending = new ArrayList<>(config.batchSize());
        Counters counters = new Counters();
        Set<String> seenMembers = new HashSet<>();
        try (RedisConnection redis = RedisConnection.connect(config.host(), config.port(), config.username(),
                config.password(), config.database(), config.ssl(), config.connectTimeoutMs(),
                config.socketTimeoutMs())) {
            if (config.clearExisting()) clearGameKeys(redis, config.redisGameId());
            else loadExistingMembers(redis, config.redisGameId(), seenMembers);
            generateNaturally(config, factory, random, redis, pending, counters, seenMembers);
            flush(redis, pending, config, counters);
        }
        return new LoadSummary(config.redisGameId(), counters.normalMembers, counters.specialMembers,
                counters.batches, counters.lossMembers, counters.winMembers);
    }

    private static void generateNaturally(LoaderConfig config, CompleteRoundFactory factory, SecureRandom random,
                                          RedisConnection redis, List<Member> pending, Counters counters,
                                          Set<String> seenMembers)
            throws Exception {
        Map<Integer, Integer> perRatio = new HashMap<>();
        int drawsInEntry = 0; long attempts=0;
        boolean specialEntry = false;
        while ((long)counters.normalMembers + counters.specialMembers < config.totalMembers()) {
            LoaderLimits.checkAttempts(++attempts,config.totalMembers());
            if (drawsInEntry >= ENTRY_SWITCH_EVERY) {
                specialEntry = !specialEntry;
                drawsInEntry = 0;
            }
            CompleteRoundFactory.GeneratedRound generated = factory.generate(random, specialEntry);
            drawsInEntry++;
            boolean special = generated.special();
            int ratio = generated.multiplierDeci();
            if (ratio < 0) {
                counters.skippedZeroRounds++;
                continue;
            }
            if (!config.acceptsMultiplier(ratio)) {
                counters.skippedRange++;
                continue;
            }
            perRatio.merge(ratio,1,Integer::sum);
            pending.add(new Member(ratio, generated.member()));
            if (special) counters.specialMembers++;
            else counters.normalMembers++;
            if (ratio == 0) counters.lossMembers++;
            else counters.winMembers++;
            if (pending.size() >= config.batchSize()) flush(redis, pending, config, counters);
        }
        System.out.printf("NATURAL_CLASSIFICATION_COMPLETE normal=%d special=%d loss=%d win=%d buckets=%d duplicates=%d%n",
                counters.normalMembers, counters.specialMembers, counters.lossMembers, counters.winMembers,
                perRatio.size(), counters.skippedDuplicateRounds);
    }

    static void loadExistingMembers(RedisConnection redis, long gameId, Set<String> seenMembers)
            throws IOException {
        for (Object ratio : asList(redis.command("ZRANGE", RedisKeys.index(gameId), "0", "-1"))) {
            int multiplier = Integer.parseInt(ratio.toString());
            String list = RedisKeys.list(gameId, multiplier);
            for (Object member : asList(redis.command("LRANGE", list, "0", "-1"))) {
                seenMembers.add(member.toString());
            }
        }
        System.out.printf("DEDUP_CACHE_LOADED uniqueMembers=%d%n", seenMembers.size());
    }

    private static void clearGameKeys(RedisConnection redis, long gameId) throws IOException {
        List<Object> normalRatios = asList(redis.command("ZRANGE", RedisKeys.index(gameId), "0", "-1"));
        List<Object> specialRatios = asList(redis.command("ZRANGE", RedisKeys.legacySplitIndex(gameId), "0", "-1"));
        List<Object> legacyRatios = asList(redis.command("ZRANGE", RedisKeys.legacyMaryIndex(gameId), "0", "-1"));
        List<String> doomed = new ArrayList<>();
        doomed.add(RedisKeys.index(gameId));
        doomed.add(RedisKeys.legacySplitIndex(gameId));
        doomed.add(RedisKeys.legacyMaryIndex(gameId));
        for (Object ratio : normalRatios) doomed.add(RedisKeys.list(gameId, Integer.parseInt(ratio.toString())));
        for (Object ratio : specialRatios) doomed.add(RedisKeys.legacySplitList(gameId, Integer.parseInt(ratio.toString())));
        for (Object ratio : legacyRatios) doomed.add(RedisKeys.legacyMaryList(gameId, Integer.parseInt(ratio.toString())));
        if (!doomed.isEmpty()) {
            List<String> args = new ArrayList<>();
            args.add("DEL");
            args.addAll(doomed);
            redis.command(args.toArray(String[]::new));
        }
        System.out.printf("CLEARED_EXISTING_KEYS gameId=%d lists=%d%n", gameId, doomed.size());
    }

    private static void flush(RedisConnection redis, List<Member> pending, LoaderConfig config, Counters counters)
            throws IOException {
        if (pending.isEmpty()) return;
        List<String[]> commands = new ArrayList<>(pending.size() * 3 + 2);
        commands.add(new String[]{"MULTI"});
        for (Member member : pending) {
            String index = RedisKeys.index(config.redisGameId());
            String list = RedisKeys.list(config.redisGameId(), member.ratio());
            commands.add(new String[]{"ZADD", index, Integer.toString(member.ratio()), Integer.toString(member.ratio())});
            commands.add(new String[]{"RPUSH", list, member.payload()});
            int cap = config.maxMembersPerMultiplier();
            commands.add(new String[]{"LTRIM", list, "-" + cap, "-1"});
        }
        commands.add(new String[]{"EXEC"});
        redis.pipeline(commands);
        counters.batches++;
        counters.loaded += pending.size();
        System.out.printf("BATCH_COMMITTED batch=%d members=%d loaded=%d%n",
                counters.batches, pending.size(), counters.loaded);
        pending.clear();
    }

    static boolean tryReserve(Map<Integer, Integer> generatedPerRatio, int ratio, int cap) {
        int current = generatedPerRatio.getOrDefault(ratio, 0);
        if (current >= cap) return false;
        generatedPerRatio.put(ratio, current + 1);
        return true;
    }

    static boolean tryReserveUnique(Map<Integer, Integer> generatedPerRatio, int ratio, int cap,
                                    Set<String> seenMembers, String member) {
        if (seenMembers.contains(member)) return false;
        if (!tryReserve(generatedPerRatio, ratio, cap)) return false;
        seenMembers.add(member);
        return true;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> list) return (List<Object>) list;
        return List.of(value);
    }

    public record LoadSummary(long redisGameId, int normalMembers, int specialMembers, int batches,
                              int lossMembers, int winMembers) { }

    private record Member(int ratio, String payload) { }

    private static final class Counters {
        int batches, loaded, normalMembers, specialMembers, lossMembers, winMembers, skippedRange, skippedCapped,
                skippedZeroRounds, skippedDuplicateRounds;
    }

    record LoaderConfig(String host, int port, String username, String password, int database, boolean ssl,
                        int connectTimeoutMs, int socketTimeoutMs, long redisGameId, int totalMembers,
                        int batchSize, int maxMembersPerMultiplier,
                        int minWinMultiplier, int maxWinMultiplier,
                        int[] symbolWeights, int[] rpxWeights, boolean clearExisting) {
        boolean acceptsMultiplier(int ratio) {
            return ratio >= minWinMultiplier && ratio <= maxWinMultiplier;
        }

        static LoaderConfig load(Path file) throws IOException {
            if (!Files.isRegularFile(file)) throw new IllegalArgumentException("config file not found: " + file);
            Properties p = new Properties();
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(reader); LoaderLimits.checkKeys(p); }
            int[] symbols = CrazyGemsBoardGenerator.defaultSymbolWeights();
            for (int i = 0; i < symbols.length; i++) {
                symbols[i] = integer(p, "generation.symbol." + CrazyGemsBoard.SYMBOLS.get(i)
                        + ".normal-weight", symbols[i]);
            }
            int[] rpx = CrazyGemsBoardGenerator.defaultRpxWeights();
            for (int i = 0; i < rpx.length; i++) {
                rpx[i] = integer(p, "generation.rpx." + CrazyGemsBoard.RPX_VALUES[i] + ".weight", rpx[i]);
            }
            LoaderConfig c = new LoaderConfig(required(p, "redis.host"), integer(p, "redis.port", 6379),
                    p.getProperty("redis.username", "").trim(), p.getProperty("redis.password", ""),
                    integer(p, "redis.database", 15), bool(p, "redis.ssl", false),
                    integer(p, "redis.connect-timeout-ms", 5000), integer(p, "redis.socket-timeout-ms", 30000),
                    longValue(p, "redis.game-id", CrazyGemsRulesMetadata.GAME_ID),
                    totalMembers(p),
                    integer(p, "generation.batch-size", 100),
                    integer(p, "generation.max-members-per-multiplier", 300),
                    integer(p, "generation.min-win-multiplier", 1),
                    integer(p, "generation.max-win-multiplier", 20000),
                    symbols, rpx,
                    bool(p, "generation.clear-existing", true));
            if (c.port < 1 || c.port > 65535 || c.database < 0 || c.redisGameId <= 0
                    || c.totalMembers < 1 || c.batchSize < 1 || c.maxMembersPerMultiplier < 1
                    || c.minWinMultiplier < 0 || c.maxWinMultiplier < c.minWinMultiplier) {
                throw new IllegalArgumentException("invalid generator.properties values");
            }
            new CompleteRoundFactory(symbols, rpx);
            return c;
        }

        private static int totalMembers(Properties p) {
            if (p.containsKey("generation.total-members")) {
                if(p.containsKey("generation.normal-count") || p.containsKey("generation.special-count"))throw new IllegalArgumentException("Conflicting generation.total-members and legacy split counts");
                return integer(p, "generation.total-members", 6000);
            }
            int normal = integer(p, "generation.normal-count", 3000);
            int special = integer(p, "generation.special-count", 3000);
            if (normal < 0 || special < 0) throw new IllegalArgumentException("generation counts must be >= 0");
            return Math.addExact(normal, special);
        }

        private static String required(Properties p, String key) {
            String value = p.getProperty(key);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("missing config: " + key);
            return value.trim();
        }

        private static int integer(Properties p, String key, int fallback) {
            return Integer.parseInt(p.getProperty(key, Integer.toString(fallback)).trim());
        }

        private static long longValue(Properties p, String key, long fallback) {
            return Long.parseLong(p.getProperty(key, Long.toString(fallback)).trim());
        }

        private static boolean bool(Properties p, String key, boolean fallback) {
            return Boolean.parseBoolean(p.getProperty(key, Boolean.toString(fallback)).trim());
        }
    }
}
