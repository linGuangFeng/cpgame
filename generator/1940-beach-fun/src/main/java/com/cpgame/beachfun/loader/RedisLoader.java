package com.cpgame.beachfun.loader;

import com.cpgame.beachfun.core.GameRuleCore;
import com.cpgame.beachfun.core.MinimalRoundFactCodec;
import com.cpgame.beachfun.core.ResultUtil;
import com.cpgame.beachfun.core.RoundFactory;
import com.cpgame.beachfun.core.RoundVerifier;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.Transaction;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * 1809-style complete-Round Redis loader. Connection, counts, batch, caps and symbol
 * weights come from generator.properties. Dealing still uses the Beach Fun TSV model.
 */
public final class RedisLoader {
    private RedisLoader() {}

    public static void main(String[] args) {
        try {
            LoadSummary summary = run(configPath(args));
            System.out.printf(Locale.ROOT,
                    "LOAD_COMPLETE redisGameId=%d normal=%d special=%d batches=%d maxConsecutiveWins=%d rulesHash=%s%n",
                    summary.redisGameId(), summary.normalMembers(), summary.specialMembers(),
                    summary.batches(), summary.maxConsecutiveWinsObserved(), GameRuleCore.RULES_HASH);
            System.out.printf(Locale.ROOT,
                    "生成完成 redisGameId=%d 普通写入=%d 特殊写入=%d 批次=%d%n",
                    summary.redisGameId(), summary.normalMembers(), summary.specialMembers(), summary.batches());
        } catch (Exception ex) {
            System.err.println("[失败] " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            System.exit(1);
        }
    }

    public static LoadSummary run(Path configFile) throws Exception {
        LoaderConfig config = LoaderConfig.load(configFile);
        GameRuleCore rules = new GameRuleCore();
        RoundFactory factory = new RoundFactory(rules);
        RoundVerifier verifier = new RoundVerifier();
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec();
        List<Member> pending = new ArrayList<>(config.batchSize());
        Counters counters = new Counters();
        Map<Integer, Integer> normalPerRatio = new HashMap<>();
        Map<Integer, Integer> specialPerRatio = new HashMap<>();
        try (Jedis redis = connect(config)) {
            int drawsInEntry = 0;
            boolean specialEntry = false;
            long attempts = 0;
            while (counters.normalMembers < config.normalCount() || counters.specialMembers < config.specialCount()) {
                if (drawsInEntry >= config.entrySwitchEvery()) {
                    specialEntry = !specialEntry;
                    drawsInEntry = 0;
                }
                LoaderLimits.checkAttempts(++attempts,(long)config.normalCount()+config.specialCount());
                GameRuleCore.CompleteRound round;
                try {
                    boolean wantSpecial = specialEntry && counters.specialMembers < config.specialCount();
                    round = wantSpecial
                            ? factory.generateForPool(RoundFactory.Requested.FREE)
                            : factory.generateNatural();
                } catch (RuntimeException rejected) {
                    counters.skippedRejected++;
                    drawsInEntry++;
                    continue;
                }
                drawsInEntry++;
                try {
                    verifier.verify(round);
                    ResultUtil.verify(round);
                } catch (RuntimeException invalid) {
                    counters.skippedRejected++;
                    continue;
                }
                if (round.totalUnits() % 20 != 0) {
                    counters.skippedNonInteger++;
                    continue;
                }
                int consecutive = maxConsecutiveWins(round);
                if (consecutive > config.maxConsecutiveWins()) {
                    counters.skippedOverlongRounds++;
                    continue;
                }
                int marySpins = Math.max(0, round.deliveries().size() - 1);
                if (marySpins > config.maxMarySpins()) {
                    counters.skippedOverlongRounds++;
                    continue;
                }
                boolean special = round.freeFeature();
                if ((special && counters.specialMembers >= config.specialCount())
                        || (!special && counters.normalMembers >= config.normalCount())) {
                    continue;
                }
                int ratio = ResultUtil.integerMultiplier(round);
                if (ratio < 0) {
                    counters.skippedZeroRounds++;
                    continue;
                }
                {
                    int min = special ? config.maryMinWinMultiplier() : config.normalMinWinMultiplier();
                    int max = special ? config.maryMaxWinMultiplier() : config.normalMaxWinMultiplier();
                    if (ratio < min || ratio > max) {
                        counters.skippedOverMaxMultiplierRounds++;
                        continue;
                    }
                }
                if (!config.outputLimits().accepts(special, ratio)) continue;
                Map<Integer, Integer> generatedPerRatio = special ? specialPerRatio : normalPerRatio;
                generatedPerRatio.merge(ratio, 1, Integer::sum);
                String payload = codec.encode(round);
                if (ResultUtil.integerMultiplier(codec.decode(payload)) != ratio) {
                    throw new IllegalStateException("codec multiplier mismatch");
                }
                pending.add(new Member(special, ratio, payload));
                counters.maxConsecutiveWins = Math.max(counters.maxConsecutiveWins, consecutive);
                if (special) counters.specialMembers++;
                else counters.normalMembers++;
                if (pending.size() >= config.batchSize()) flush(redis, pending, config, counters);
            }
            flush(redis, pending, config, counters);
        }
        System.out.printf(Locale.ROOT,
                "NATURAL_CLASSIFICATION_COMPLETE normal=%d special=%d normalBuckets=%d specialBuckets=%d capPerMultiplier=%d discardedOverlong=%d%n",
                counters.normalMembers, counters.specialMembers, normalPerRatio.size(), specialPerRatio.size(),
                config.maxMembersPerMultiplier(), counters.skippedOverlongRounds);
        return new LoadSummary(config.redisGameId(), counters.normalMembers, counters.specialMembers,
                counters.batches, counters.maxConsecutiveWins);
    }

    static boolean tryReserveMultiplier(Map<Integer, Integer> generatedPerRatio, int ratio, int cap) {
        int current = generatedPerRatio.getOrDefault(ratio, 0);
        if (current >= cap) return false;
        generatedPerRatio.put(ratio, current + 1);
        return true;
    }

    static int maxConsecutiveWins(GameRuleCore.CompleteRound round) {
        int max = 0;
        for (var delivery : round.deliveries()) {
            max = Math.max(max, Math.max(0, delivery.cascades().size() - 1));
        }
        return max;
    }

    private static void flush(Jedis redis, List<Member> pending, LoaderConfig config, Counters counters) {
        if (pending.isEmpty()) return;
        Transaction tx = redis.multi();
        for (Member member : pending) {
            String index = member.special()
                    ? String.format(Locale.ROOT, "MaryKeyList_%09d", config.redisGameId())
                    : String.format(Locale.ROOT, "PerKeyList_%09d", config.redisGameId());
            String list = member.special()
                    ? String.format(Locale.ROOT, "MaryLog:%09d:%06d", config.redisGameId(), member.ratio())
                    : String.format(Locale.ROOT, "BetLog:0%08d:%06d", config.redisGameId(), member.ratio());
            tx.zadd(index, member.ratio(), Integer.toString(member.ratio()));
            tx.rpush(list, member.payload());
            tx.ltrim(list, -(member.special() ? config.outputLimits().specialCap : config.maxMembersPerMultiplier()), -1);
        }
        tx.exec();
        counters.batches++;
        counters.loaded += pending.size();
        System.out.printf(Locale.ROOT, "BATCH_COMMITTED batch=%d members=%d loaded=%d%n",
                counters.batches, pending.size(), counters.loaded);
        System.out.printf(Locale.ROOT, "批次已提交 batch=%d members=%d loaded=%d%n",
                counters.batches, pending.size(), counters.loaded);
        pending.clear();
    }

    static Jedis connect(LoaderConfig config) {
        JedisClientConfig client = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(config.connectTimeoutMs())
                .socketTimeoutMillis(config.socketTimeoutMs())
                .user(config.username().isBlank() ? null : config.username())
                .password(config.password().isBlank() ? null : config.password())
                .database(config.database())
                .ssl(config.ssl())
                .build();
        Jedis redis = new Jedis(config.host(), config.port(), client);
        redis.connect();
        if (!"PONG".equalsIgnoreCase(redis.ping())) {
            redis.close();
            throw new IllegalStateException("Redis PING failed");
        }
        return redis;
    }

    static Path configPath(String[] args) throws Exception {
        Path jar = Path.of(RedisLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path file = (Files.isDirectory(jar) ? jar : jar.getParent()).resolve("generator.properties");
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--no-pause")) continue;
            if (arg.equals("--config") && i + 1 < args.length) file = Path.of(args[++i]);
            else if (arg.startsWith("--config=")) file = Path.of(arg.substring(9));
            else if (!arg.startsWith("--")) file = Path.of(arg);
            else throw new IllegalArgumentException("unsupported argument " + arg);
        }
        return file.toAbsolutePath().normalize();
    }

    public record LoadSummary(long redisGameId, int normalMembers, int specialMembers, int batches,
                              int maxConsecutiveWinsObserved) {}
    private record Member(boolean special, int ratio, String payload) {}
    private static final class Counters {
        int batches, loaded, maxConsecutiveWins, normalMembers, specialMembers;
        int skippedZeroRounds, skippedCappedRounds, skippedOverlongRounds, skippedOverMaxMultiplierRounds,
                skippedNonInteger, skippedRejected;
    }

    public record LoaderConfig(String host, int port, String username, String password, int database, boolean ssl,
                               int connectTimeoutMs, int socketTimeoutMs, long redisGameId, int normalCount,
                               int specialCount, int batchSize, int maxConsecutiveWins, int maxMarySpins,
                               int maxMembersPerMultiplier, int entrySwitchEvery,
                               int normalMinWinMultiplier, int normalMaxWinMultiplier,
                               int maryMinWinMultiplier, int maryMaxWinMultiplier,
                               int[] normalWeights, int[] maryWeights, LoaderLimits outputLimits) {
        public LoaderConfig(String host, int port, String username, String password, int database, boolean ssl,
                               int connectTimeoutMs, int socketTimeoutMs, long redisGameId, int normalCount,
                               int specialCount, int batchSize, int maxConsecutiveWins, int maxMarySpins,
                               int maxMembersPerMultiplier, int entrySwitchEvery,
                               int normalMinWinMultiplier, int normalMaxWinMultiplier,
                               int maryMinWinMultiplier, int maryMaxWinMultiplier,
                               int[] normalWeights, int[] maryWeights) { this(host, port, username, password, database, ssl, connectTimeoutMs, socketTimeoutMs, redisGameId, normalCount, specialCount, batchSize, maxConsecutiveWins, maxMarySpins, maxMembersPerMultiplier, entrySwitchEvery, normalMinWinMultiplier, normalMaxWinMultiplier, maryMinWinMultiplier, maryMaxWinMultiplier, normalWeights, maryWeights, new LoaderLimits(new java.util.Properties())); }

        private static final int MAX_TARGET = Integer.MAX_VALUE;

        public static LoaderConfig load(Path file) throws IOException {
            if (!Files.isRegularFile(file)) throw new IllegalArgumentException("config file not found: " + file);
            Properties p = new Properties();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(reader); LoaderLimits.checkKeys(p); }
            return from(p);
        }

        static LoaderConfig from(Properties p) {
            for (String key : p.stringPropertyNames()) {
                if (key.toLowerCase(Locale.ROOT).contains("seed")) {
                    throw new IllegalArgumentException("Formal loader does not accept a seed");
                }
            }
            for(String key:p.stringPropertyNames())if(key.startsWith("generation.symbol."))throw new IllegalArgumentException("当前使用固定联合模型，不支持旧字段: "+key);
            int cap = firstPresent(p, 300, "generation.max-members-per-multiplier", "redis.max-members-per-bucket");
            int maryMin = firstPresent(p, 1, "generation.mary-min-win-multiplier", "generation.special-min-win-multiplier");
            int maryMax = firstPresent(p, 20_000, "generation.mary-max-win-multiplier", "generation.special-max-win-multiplier");
            LoaderConfig c = new LoaderConfig(
                    required(p, "redis.host"),
                    integer(p, "redis.port", 6379),
                    p.getProperty("redis.username", "").trim(),
                    p.getProperty("redis.password", ""),
                    integer(p, "redis.database", 15),
                    bool(p, "redis.ssl", false),
                    integer(p, "redis.connect-timeout-ms", 5000),
                    integer(p, "redis.socket-timeout-ms", 30000),
                    longValue(p, "redis.game-id", 1940L),
                    integer(p, "generation.normal-count", 100_000_000),
                    integer(p, "generation.special-count", 1_000_000),
                    integer(p, "generation.batch-size", 100),
                    integer(p, "generation.max-consecutive-wins", 14),
                    integer(p, "generation.max-mary-spins", 15),
                    cap,
                    integer(p, "generation.entry-switch-every", 1000),
                    integer(p, "generation.normal-min-win-multiplier", 1),
                    integer(p, "generation.normal-max-win-multiplier", 20_000),
                    maryMin,
                    maryMax,
                    weights(p, "normal"),
                    weights(p, "mary"), new LoaderLimits(p));
            if (c.port < 1 || c.port > 65535 || c.database < 0 || c.redisGameId <= 0
                    || c.normalCount < 0 || c.specialCount < 0 || c.normalCount + c.specialCount == 0
                    || c.normalCount > MAX_TARGET || c.specialCount > MAX_TARGET
                    || c.batchSize < 1 || c.batchSize > 10_000 || c.maxConsecutiveWins < 1
                    || c.maxMarySpins < 1 || c.entrySwitchEvery < 1
                    || c.normalMinWinMultiplier < 0 || c.maryMinWinMultiplier < 0
                    || c.normalMaxWinMultiplier < 1 || c.maryMaxWinMultiplier < 1
                    || c.normalMinWinMultiplier > c.normalMaxWinMultiplier
                    || c.maryMinWinMultiplier > c.maryMaxWinMultiplier
                    || !validWeights(c.normalWeights) || !validWeights(c.maryWeights)
                    || c.maxMembersPerMultiplier < 1
                    || c.connectTimeoutMs < 1 || c.socketTimeoutMs < 1) {
                throw new IllegalArgumentException("invalid generator.properties values");
            }
            return c;
        }

        private static int[] weights(Properties p, String mode) {
            int[] result = new int[10];
            for (int symbol = 1; symbol <= 10; symbol++) {
                result[symbol - 1] = integer(p, "generation.symbol." + symbol + "." + mode + "-weight",
                        symbol == 10 ? 0 : 1);
            }
            return result;
        }

        private static boolean validWeights(int[] values) {
            if (values == null || values.length != 10) return false;
            long total = 0;
            for (int i = 0; i < 9; i++) {
                if (values[i] <= 0) return false;
                total += values[i];
            }
            if (values[9] < 0) return false;
            return total > 0;
        }

        private static String required(Properties p, String key) {
            String value = p.getProperty(key);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("missing config: " + key);
            return value.trim();
        }

        private static int integer(Properties p, String key, int fallback) {
            return Integer.parseInt(p.getProperty(key, Integer.toString(fallback)).trim());
        }

        private static int firstPresent(Properties p, int fallback, String... keys) {
            for (String key : keys) {
                String value = p.getProperty(key);
                if (value != null && !value.isBlank()) return Integer.parseInt(value.trim());
            }
            return fallback;
        }

        private static long longValue(Properties p, String key, long fallback) {
            return Long.parseLong(p.getProperty(key, Long.toString(fallback)).trim());
        }

        private static boolean bool(Properties p, String key, boolean fallback) {
            return Boolean.parseBoolean(p.getProperty(key, Boolean.toString(fallback)).trim());
        }
    }
}
