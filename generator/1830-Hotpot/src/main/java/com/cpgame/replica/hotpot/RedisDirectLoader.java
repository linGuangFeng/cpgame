package com.cpgame.replica.hotpot;

import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotBoardGenerator;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotGameRuleCore;
import com.hd.pg.appapi.business.model.cpgame.hotpot.HotpotRoundKind;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.net.ssl.SSLSocketFactory;

/** One-command complete-Round generator that commits each configured batch directly to Redis. */
public final class RedisDirectLoader {
    static final int ENTRY_SWITCH_EVERY = 1000;
    static final int SPECIAL_TRIGGER_WEIGHT_MULTIPLIER =
            HotpotBoardGenerator.SPECIAL_TRIGGER_WEIGHT_MULTIPLIER;

    private RedisDirectLoader() { }

    public static void main(String[] args) {
        try {
            Path config = Path.of("generator.properties").toAbsolutePath().normalize();
            for (String arg : args) {
                if ("--no-pause".equals(arg)) continue;
                config = Path.of(arg).toAbsolutePath().normalize();
            }
            LoadSummary summary = run(config);
            System.out.printf("LOAD_COMPLETE redisGameId=%d loss=%d normal=%d special=%d batches=%d maxConsecutiveWins=%d rulesVersion=%s rulesHash=%s protocolHash=%s%n",
                    summary.redisGameId(), summary.lossMembers(), summary.normalMembers(), summary.specialMembers(),
                    summary.batches(), summary.maxConsecutiveWinsObserved(),
                    HotpotRulesMetadata.VERSION, HotpotRulesMetadata.HASH, HotpotRulesMetadata.PROTOCOL_HASH);
        } catch (Exception ex) {
            System.err.println("[失败] " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            System.exit(1);
        }
    }

    public static LoadSummary run(Path configFile) throws Exception {
        LoaderConfig config = LoaderConfig.load(configFile);
        HotpotGameRuleCore core = new HotpotGameRuleCore();
        if (!core.implementationAllowed() || core.rawGameId() != 1830) {
            throw new IllegalStateException("GameRuleCore refused gid 1830");
        }
        CompleteRoundFactory factory = new CompleteRoundFactory();
        CompleteRoundCodec codec = new CompleteRoundCodec();
        SecureRandom random = new SecureRandom();
        List<Member> pending = new ArrayList<>(config.batchSize());
        Counters counters = new Counters();
        try (RedisConnection redis = RedisConnection.connect(config)) {
            fillIndependentLosses(config, factory, codec, random, redis, pending, counters);
            generateNaturally(config, factory, codec, random, redis, pending, counters);
            flush(redis, pending, config, counters);
        }
        return new LoadSummary(config.redisGameId(), counters.lossMembers, config.normalCount(), config.specialCount(),
                counters.batches, counters.maxConsecutiveWins);
    }

    private static void fillIndependentLosses(LoaderConfig config, CompleteRoundFactory factory,
                                              CompleteRoundCodec codec, SecureRandom random,
                                              RedisConnection redis, List<Member> pending,
                                              Counters counters) throws Exception {
        Map<Integer, Integer> lossPerRatio = new HashMap<>();
        int target = config.normalMinWinMultiplier() == 0
                ? (config.normalMaxWinMultiplier() == 0 ? config.normalCount()
                    : Math.min(config.normalCount(), config.maxMembersPerMultiplier())) : 0;
        while (counters.lossMembers < target) {
            CompleteRoundFactory.GeneratedRound generated = factory.generateIndependentLoss(random);
            if (generated.kind() != HotpotRoundKind.ORDINARY_LOSS || generated.multiplier() != 0) {
                throw new IllegalStateException("independent loss was not ORDINARY_LOSS/0");
            }
            if (!tryReserveMultiplier(lossPerRatio, 0, target)) break;
            String payload = codec.encode(generated.fact());
            RoundVerification verification = codec.verify(payload, config.maxConsecutiveWins(), config.maxMarySpins());
            if (verification.multiplier() != 0 || verification.scatterFreeSpins()) {
                throw new IllegalStateException("loss member failed verification");
            }
            pending.add(new Member(false, 0, payload));
            counters.lossMembers++;
            if (pending.size() >= config.batchSize()) flush(redis, pending, config, counters);
        }
        System.out.printf("INDEPENDENT_LOSS_PRELOAD loss=%d capPerMultiplier=%d%n",
                counters.lossMembers, config.maxMembersPerMultiplier());
    }

    private static void generateNaturally(LoaderConfig config, CompleteRoundFactory factory,
                                          CompleteRoundCodec codec, SecureRandom random,
                                          RedisConnection redis, List<Member> pending,
                                          Counters counters) throws Exception {
        Map<Integer, Integer> normalPerRatio = new HashMap<>();
        Map<Integer, Integer> specialPerRatio = new HashMap<>();
        int[] ordinaryOpening = config.normalWeights();
        int[] specialOpening = specialEntryOpeningWeights(ordinaryOpening);
        int normalMembers = counters.lossMembers;
        int specialMembers = 0;
        int drawsInEntry = 0;
        boolean specialEntry = false;
        long attempts = 0;
        long attemptLimit = Math.max(100_000L, ((long) config.normalCount() + config.specialCount()) * 10_000L);
        while (normalMembers < config.normalCount() || specialMembers < config.specialCount()) {
            Member selected;
            while (true) {
                if (++attempts > attemptLimit) throw new IllegalStateException("配置范围/权重/容量内无法完成生成目标，已达到候选上限");
                if (drawsInEntry >= ENTRY_SWITCH_EVERY) {
                    specialEntry = !specialEntry;
                    drawsInEntry = 0;
                }
                CompleteRoundFactory.GeneratedRound generated;
                try {
                    generated = factory.generate(random, config.maxConsecutiveWins(), config.maxMarySpins(),
                            specialEntry ? specialOpening : ordinaryOpening,
                            config.cascadeWeights(), config.maryWeights());
                } catch (CompleteRoundFactory.RoundRejectedException rejected) {
                    counters.skippedCandidateLimitRounds++;
                    drawsInEntry++;
                    continue;
                }
                drawsInEntry++;
                boolean special = generated.kind() == HotpotRoundKind.SCATTER_FREE_SPINS;
                int ratio = generated.multiplier();
                if (ratio < 0) throw new IllegalStateException("negative multiplier");
                if (!special && ratio == 0) {
                    counters.skippedZeroRounds++;
                    continue;
                }
                if ((special && specialMembers >= config.specialCount())
                        || (!special && normalMembers >= config.normalCount())) continue;
                int minWinMultiplier = special ? config.maryMinWinMultiplier() : config.normalMinWinMultiplier();
                int maxWinMultiplier = special ? config.maryMaxWinMultiplier() : config.normalMaxWinMultiplier();
                if (ratio < minWinMultiplier || ratio > maxWinMultiplier) {
                    counters.skippedOverMaxMultiplierRounds++;
                    continue;
                }
                Map<Integer, Integer> generatedPerRatio = special ? specialPerRatio : normalPerRatio;
                int memberCap = special ? config.specialMaxMembersPerMultiplier() : config.maxMembersPerMultiplier();
                generatedPerRatio.merge(ratio, 1, Integer::sum);
                String payload = codec.encode(generated.fact());
                RoundVerification verification = codec.verify(payload, config.maxConsecutiveWins(),
                        config.maxMarySpins());
                if (verification.multiplier() != generated.multiplier()) {
                    throw new IllegalStateException("codec multiplier mismatch");
                }
                if (verification.scatterFreeSpins() != special) {
                    throw new IllegalStateException("codec special classification mismatch");
                }
                selected = new Member(special, ratio, payload);
                counters.maxConsecutiveWins = Math.max(counters.maxConsecutiveWins, verification.maxConsecutiveWins());
                break;
            }
            pending.add(selected);
            if (selected.special()) specialMembers++;
            else normalMembers++;
            if (pending.size() >= config.batchSize()) flush(redis, pending, config, counters);
        }
        System.out.printf("NATURAL_CLASSIFICATION_COMPLETE normal=%d special=%d lossPreloaded=%d normalBuckets=%d specialBuckets=%d capPerMultiplier=%d discardedCandidateLimit=%d%n",
                normalMembers, specialMembers, counters.lossMembers, normalPerRatio.size(), specialPerRatio.size(),
                config.maxMembersPerMultiplier(), counters.skippedCandidateLimitRounds);
    }

    /**
     * 特殊入口只把付费首局 Scatter 概率放大 10 倍以便抽到触发局。
     * 连消/免费仍用原倍数；个数仍受抓包上限约束：同列最多 1 个，付费盘最多 4 个。
     */
    static int[] specialEntryOpeningWeights(int[] ordinary) {
        return HotpotBoardGenerator.specialEntryOpeningWeights(ordinary);
    }

    private static void flush(RedisConnection redis, List<Member> pending, LoaderConfig config, Counters counters)
            throws IOException {
        if (pending.isEmpty()) return;
        List<String[]> commands = new ArrayList<>(pending.size() * 3 + 2);
        commands.add(new String[]{"MULTI"});
        for (Member member : pending) {
            String index = member.special()
                    ? RedisKeys.maryIndex(config.redisGameId()) : RedisKeys.normalIndex(config.redisGameId());
            String list = member.special()
                    ? RedisKeys.maryList(config.redisGameId(), member.ratio())
                    : RedisKeys.normalList(config.redisGameId(), member.ratio());
            commands.add(new String[]{"ZADD", index, Integer.toString(member.ratio()), Integer.toString(member.ratio())});
            commands.add(new String[]{"RPUSH", list, member.payload()});
            int cap = member.special() ? config.specialMaxMembersPerMultiplier() : config.maxMembersPerMultiplier();
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

    static boolean tryReserveMultiplier(Map<Integer, Integer> generatedPerRatio, int ratio, int cap) {
        int current = generatedPerRatio.getOrDefault(ratio, 0);
        if (current >= cap) return false;
        generatedPerRatio.put(ratio, current + 1);
        return true;
    }

    public record LoadSummary(long redisGameId, int lossMembers, int normalMembers, int specialMembers,
                              int batches, int maxConsecutiveWinsObserved) { }
    private record Member(boolean special, int ratio, String payload) { }
    private static final class Counters {
        int batches, loaded, maxConsecutiveWins, skippedZeroRounds, skippedCappedRounds, skippedCandidateLimitRounds,
                skippedOverMaxMultiplierRounds, lossMembers;
    }

    record LoaderConfig(String host, int port, String username, String password, int database, boolean ssl,
                        int connectTimeoutMs, int socketTimeoutMs, long redisGameId, int normalCount,
                        int specialCount, int batchSize, int maxConsecutiveWins,
                        int maxMarySpins, int maxMembersPerMultiplier, int specialMaxMembersPerMultiplier,
                        int normalMinWinMultiplier, int normalMaxWinMultiplier,
                        int maryMinWinMultiplier, int maryMaxWinMultiplier,
                        int[] normalWeights, int[] cascadeWeights, int[] maryWeights) {
        private static final Set<String> KNOWN_KEYS = knownKeys();

        static LoaderConfig load(Path file) throws IOException {
            if (!Files.isRegularFile(file)) throw new IllegalArgumentException("config file not found: " + file);
            Properties p = new Properties();
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(reader); LoaderLimits.checkKeys(p); }
            for (String key : p.stringPropertyNames()) {
                String normalized = key.toLowerCase();
                if (normalized.contains("seed")) {
                    throw new IllegalArgumentException("formal config must not contain seed: " + key);
                }
                if (!KNOWN_KEYS.contains(key)) {
                    throw new IllegalArgumentException("unread generator.properties key: " + key);
                }
            }
            LoaderConfig c = new LoaderConfig(required(p, "redis.host"), integer(p, "redis.port", 6379),
                    p.getProperty("redis.username", "").trim(), p.getProperty("redis.password", ""),
                    integer(p, "redis.database", 0), bool(p, "redis.ssl", false),
                    integer(p, "redis.connect-timeout-ms", 5000), integer(p, "redis.socket-timeout-ms", 30000),
                    longValue(p, "redis.game-id", 1830L), integer(p, "generation.normal-count", 100_000_000),
                    integer(p, "generation.special-count", 100_000_000), integer(p, "generation.batch-size", 100),
                    integer(p, "generation.max-consecutive-wins", 10),
                    integer(p, "generation.max-mary-spins", 30),
                    integer(p, "generation.max-members-per-multiplier", 300),
                    integer(p, "generation.special-max-members-per-multiplier", 100),
                    integer(p, "generation.normal-min-win-multiplier", 1),
                    integer(p, "generation.normal-max-win-multiplier", 20_000),
                    integer(p, "generation.mary-min-win-multiplier", 100),
                    integer(p, "generation.mary-max-win-multiplier", 20_000),
                    weights(p, "normal", HotpotBoardGenerator.defaultPaidStartWeights()),
                    weights(p, "cascade", HotpotBoardGenerator.defaultCascadeWeights()),
                    weights(p, "mary", HotpotBoardGenerator.defaultFreeStartWeights()));
            readKnown(p);
            if (c.port < 1 || c.port > 65535 || c.database < 0 || c.redisGameId <= 0
                    || c.normalCount < 0 || c.specialCount < 0 || c.normalCount + c.specialCount == 0
                    || c.batchSize < 1 || c.batchSize > 10_000 || c.maxConsecutiveWins < 1
                    || c.maxMarySpins < 1
                    || c.normalMinWinMultiplier < 0 || c.maryMinWinMultiplier < 0
                    || c.normalMaxWinMultiplier < 0 || c.maryMaxWinMultiplier < 0
                    || c.normalMinWinMultiplier > c.normalMaxWinMultiplier
                    || c.maryMinWinMultiplier > c.maryMaxWinMultiplier
                    || !validWeights(c.normalWeights) || !validWeights(c.cascadeWeights) || !validWeights(c.maryWeights)
                    || c.maxMembersPerMultiplier < 1 || c.specialMaxMembersPerMultiplier < 1) {
                throw new IllegalArgumentException("invalid generator.properties values");
            }
            return c;
        }

        private static void readKnown(Properties p) {
            for (String key : KNOWN_KEYS) p.getProperty(key);
        }

        private static Set<String> knownKeys() {
            Set<String> keys = new LinkedHashSet<>();
            keys.add("redis.host");
            keys.add("redis.port");
            keys.add("redis.username");
            keys.add("redis.password");
            keys.add("redis.database");
            keys.add("redis.ssl");
            keys.add("redis.connect-timeout-ms");
            keys.add("redis.socket-timeout-ms");
            keys.add("redis.game-id");
            keys.add("generation.normal-count");
            keys.add("generation.special-count");
            keys.add("generation.batch-size");
            keys.add("generation.max-members-per-multiplier");
            keys.add("generation.special-max-members-per-multiplier");
            keys.add("generation.max-consecutive-wins");
            keys.add("generation.max-mary-spins");
            keys.add("generation.normal-min-win-multiplier");
            keys.add("generation.normal-max-win-multiplier");
            keys.add("generation.mary-min-win-multiplier");
            keys.add("generation.mary-max-win-multiplier");
            for (int symbol = 1; symbol <= HotpotBoardGenerator.SYMBOL_COUNT; symbol++) {
                keys.add("generation.symbol." + symbol + ".normal-weight");
                keys.add("generation.symbol." + symbol + ".cascade-weight");
                keys.add("generation.symbol." + symbol + ".mary-weight");
            }
            return Set.copyOf(keys);
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
        private static int[] weights(Properties p, String mode, int[] defaults) {
            int[] result = defaults.clone();
            for (int symbol = 1; symbol <= result.length; symbol++) {
                result[symbol - 1] = integer(p,
                        "generation.symbol." + symbol + "." + mode + "-weight", result[symbol - 1]);
            }
            return result;
        }
        private static boolean validWeights(int[] values) {
            long total = 0;
            if (values == null || values.length != HotpotBoardGenerator.SYMBOL_COUNT) return false;
            for (int value : values) {
                if (value < 0) return false;
                total += value;
            }
            return total > 0;
        }
    }

    static final class RedisConnection implements AutoCloseable {
        private final Socket socket;
        private final InputStream input;
        private final OutputStream output;

        private RedisConnection(Socket socket) throws IOException {
            this.socket = socket;
            this.input = new BufferedInputStream(socket.getInputStream());
            this.output = new BufferedOutputStream(socket.getOutputStream());
        }

        static RedisConnection connect(LoaderConfig config) throws IOException {
            System.out.printf("REDIS_CONNECTING host=%s port=%d database=%d ssl=%s%n",
                    config.host(), config.port(), config.database(), config.ssl());
            Socket socket = config.ssl() ? SSLSocketFactory.getDefault().createSocket() : new Socket();
            try {
                socket.connect(new InetSocketAddress(config.host(), config.port()), config.connectTimeoutMs());
            } catch (IOException refused) {
                throw new IOException("无法连接 Redis %s:%d。先确认网络与 generator.properties 的 redis.host / redis.port / redis.database，不准改出牌规则。"
                        .formatted(config.host(), config.port()), refused);
            }
            socket.setSoTimeout(config.socketTimeoutMs());
            RedisConnection connection = new RedisConnection(socket);
            try {
                if (!config.password().isBlank()) {
                    if (config.username().isBlank()) connection.command("AUTH", config.password());
                    else connection.command("AUTH", config.username(), config.password());
                }
                if (config.database() != 0) connection.command("SELECT", Integer.toString(config.database()));
                Object pong = connection.command("PING");
                if (!"PONG".equals(pong)) throw new IOException("Redis PING returned: " + pong);
                System.out.printf("REDIS_CONNECTED host=%s port=%d database=%d ssl=%s%n",
                        config.host(), config.port(), config.database(), config.ssl());
                return connection;
            } catch (Exception ex) {
                connection.close();
                if (ex instanceof IOException io) throw io;
                throw new IOException("Redis connection initialization failed host=%s port=%d"
                        .formatted(config.host(), config.port()), ex);
            }
        }

        Object command(String... args) throws IOException {
            write(args); output.flush(); return read();
        }

        List<Object> pipeline(List<String[]> commands) throws IOException {
            for (String[] command : commands) write(command);
            output.flush();
            List<Object> replies = new ArrayList<>(commands.size());
            for (int i = 0; i < commands.size(); i++) replies.add(read());
            Object exec = replies.get(replies.size() - 1);
            if (!(exec instanceof List<?> values) || values.size() != commands.size() - 2) {
                throw new IOException("Redis EXEC response count mismatch");
            }
            return replies;
        }

        private void write(String[] args) throws IOException {
            output.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            for (String arg : args) {
                byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
                output.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
                output.write(bytes); output.write('\r'); output.write('\n');
            }
        }

        private Object read() throws IOException {
            int prefix = input.read();
            if (prefix < 0) throw new EOFException("Redis closed the connection");
            return switch (prefix) {
                case '+' -> line();
                case '-' -> throw new IOException("Redis error: " + line());
                case ':' -> Long.parseLong(line());
                case '$' -> bulk();
                case '*' -> array();
                default -> throw new IOException("invalid Redis RESP prefix: " + (char) prefix);
            };
        }

        private String line() throws IOException {
            var bytes = new java.io.ByteArrayOutputStream();
            int previous = -1;
            while (true) {
                int current = input.read();
                if (current < 0) throw new EOFException();
                if (previous == '\r' && current == '\n') break;
                if (previous >= 0) bytes.write(previous);
                previous = current;
            }
            return bytes.toString(StandardCharsets.UTF_8);
        }

        private Object bulk() throws IOException {
            int length = Integer.parseInt(line());
            if (length < 0) return null;
            byte[] value = input.readNBytes(length);
            if (value.length != length || input.read() != '\r' || input.read() != '\n') throw new EOFException();
            return new String(value, StandardCharsets.UTF_8);
        }

        private Object array() throws IOException {
            int length = Integer.parseInt(line());
            if (length < 0) return null;
            List<Object> result = new ArrayList<>(length);
            for (int i = 0; i < length; i++) result.add(read());
            return result;
        }

        @Override public void close() throws IOException { socket.close(); }
    }
}
