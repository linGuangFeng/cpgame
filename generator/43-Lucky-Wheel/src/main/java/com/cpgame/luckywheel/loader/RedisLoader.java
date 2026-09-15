package com.cpgame.luckywheel.loader;

import com.cpgame.luckywheel.core.GameRound;
import com.cpgame.luckywheel.core.GameRuleCore;
import com.cpgame.luckywheel.core.MinimalFactCodec;
import com.cpgame.luckywheel.core.OutcomeType;
import com.cpgame.luckywheel.core.ResultAnalysis;
import com.cpgame.luckywheel.core.ResultUtil;
import com.cpgame.luckywheel.core.RoundFacts;
import com.cpgame.luckywheel.core.RoundRequest;

import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** SecureRandom 自然生成、独立反推并直接原子写入 Redis 的正式 Loader。 */
public final class RedisLoader {
    private static final BigDecimal GENERATION_BALANCE = new BigDecimal("1000000.00");
    private static final int MAX_TARGET = Integer.MAX_VALUE;

    public RunResult run(Path configPath) throws Exception {
        LoaderConfig config = LoaderConfig.load(configPath);
        GameRuleCore core = new GameRuleCore(config.outcomeWeights(), config.jointStateWeights());
        MinimalFactCodec codec = new MinimalFactCodec();
        List<Member> pending = new ArrayList<>(config.batchSize());
        Counters counters = new Counters();
        try (RedisConnection redis = RedisConnection.connect(config)) {
            clearExistingGameKeys(redis, config.redisGameId());
            generate(config, core, codec, redis, pending, counters);
            flush(redis, pending, config, counters);
            verifyPools(redis, config.redisGameId());
        }
        return new RunResult(GameRuleCore.SOURCE_GAME_ID, config.redisGameId(), GameRuleCore.RULES_HASH,
                counters.normalMembers, counters.specialMembers, counters.loaded, counters.batches,
                counters.maxConsecutiveWins, counters.normalLossMembers, counters.skippedByWeight,
                counters.skippedOverLimit, counters.normalMultipliers.size(), counters.specialMultipliers.size());
    }

    private static void generate(LoaderConfig config, GameRuleCore core, MinimalFactCodec codec,
                                 RedisConnection redis, List<Member> pending,
                                 Counters counters) throws Exception {
        long target = (long) config.normalCount() + config.specialCount();
        long maxAttempts = Math.max(100_000L, target * 100_000L);
        while (counters.normalMembers < config.normalCount() || counters.specialMembers < config.specialCount()) {
            if (++counters.attempts > maxAttempts) {
                throw new IllegalStateException("生成门禁在最大尝试次数内无法达到配置目标，请检查权重或倍率上限");
            }
            int profile = selectProfile(config, counters);
            int normalTarget = targetFor(config.normalCount(), profile);
            int specialTarget = targetFor(config.specialCount(), profile);
            boolean requireLoss = counters.normalByProfile[profile] + 1 == normalTarget
                    && config.outputLimits().accepts(false, 0) && counters.lossByProfile[profile] == 0;
            GameRound round = requireLoss
                    ? core.generateIndependentLoss(new RoundRequest(profile, 1, GENERATION_BALANCE))
                    : core.generateRound(new RoundRequest(profile, 1, GENERATION_BALANCE));
            RoundFacts facts = codec.extract(round);
            ResultAnalysis generatedAnalysis = ResultUtil.analyze(facts);
            boolean special = generatedAnalysis.outcome() == OutcomeType.MULTIPLIER_MD1
                    || generatedAnalysis.outcome() == OutcomeType.RESPIN_MD2
                    || generatedAnalysis.outcome() == OutcomeType.SCATTER_LUCKY_WHEEL_MD3;
            if (special != (facts.mode() != 0)) {
                throw new IllegalStateException("模式与奖池分类不一致: mode=" + facts.mode());
            }
            if (special && counters.specialByProfile[profile] >= specialTarget) continue;
            if (!special && counters.normalByProfile[profile] >= normalTarget) continue;
            int multiplier = exactNonNegativeMultiplier(generatedAnalysis.totalAward());
            int maxMultiplier = special ? config.specialMaxWinMultiplier() : config.normalMaxWinMultiplier();
            int consecutiveWins = consecutiveWinningStages(facts);
            if (!config.outputLimits().accepts(special, multiplier) || multiplier > maxMultiplier || consecutiveWins > config.maxConsecutiveWins()) {
                counters.skippedOverLimit++;
                continue;
            }

            byte[] encoded = codec.encodeRedisMember(facts);
            RoundFacts decoded = codec.decodeRedisMember(encoded);
            ResultAnalysis decodedAnalysis = ResultUtil.analyze(decoded);
            if (!facts.equals(decoded) || generatedAnalysis.outcome() != decodedAnalysis.outcome()
                    || generatedAnalysis.totalAward().compareTo(decodedAnalysis.totalAward()) != 0) {
                throw new IllegalStateException("Redis member 独立解码或 ResultUtil 反推不一致");
            }

            pending.add(new Member(profile, special, multiplier,
                    new String(encoded, StandardCharsets.US_ASCII)));
            if (special) {
                counters.specialMembers++;
                counters.specialByProfile[profile]++;
                counters.specialMultipliers.add(multiplier);
            } else {
                counters.normalMembers++;
                counters.normalByProfile[profile]++;
                counters.normalMultipliers.add(multiplier);
                if (multiplier == 0) { counters.normalLossMembers++; counters.lossByProfile[profile]++; }
            }
            counters.maxConsecutiveWins = Math.max(counters.maxConsecutiveWins, consecutiveWins);
            if (pending.size() >= config.batchSize()) flush(redis, pending, config, counters);
        }
    }

    private static int targetFor(int total, int profile) {
        return profile == 1 ? (total + 1) / 2 : total / 2;
    }

    private static int selectProfile(LoaderConfig config, Counters counters) {
        boolean lowDone = counters.normalByProfile[1] >= targetFor(config.normalCount(), 1)
                && counters.specialByProfile[1] >= targetFor(config.specialCount(), 1);
        boolean highDone = counters.normalByProfile[5] >= targetFor(config.normalCount(), 5)
                && counters.specialByProfile[5] >= targetFor(config.specialCount(), 5);
        if (lowDone) return 5;
        if (highDone) return 1;
        return (counters.attempts & 1L) == 0 ? 1 : 5;
    }

    private static int consecutiveWinningStages(RoundFacts facts) {
        int count = ResultUtil.independentScore(facts.baseSymbols()).signum() > 0 ? 1 : 0;
        if (facts.mode() == 2 && ResultUtil.independentScore(facts.respinSymbols()).signum() > 0) count++;
        return count;
    }

    private static int exactNonNegativeMultiplier(BigDecimal value) {
        if (value.signum() < 0) throw new IllegalStateException("反推倍率不得为负数: " + value);
        try { return value.stripTrailingZeros().intValueExact(); }
        catch (ArithmeticException ex) { throw new IllegalStateException("反推倍率不是非负整数: " + value, ex); }
    }

    static void clearExistingGameKeys(RedisConnection redis, long gameId) throws IOException {
        String pad8 = String.format(Locale.ROOT, "%08d", gameId);
        String pad9 = String.format(Locale.ROOT, "%09d", gameId);
        LinkedHashSet<String> doomed = new LinkedHashSet<>();
        for (String pattern : List.of(
                "PerKeyList_*" + pad8 + "*",
                "MaryKeyList_*" + pad8 + "*",
                "BetLog:*" + pad8 + "*",
                "MaryLog:*" + pad8 + "*",
                "PerKeyList_*" + pad9 + "*",
                "MaryKeyList_*" + pad9 + "*",
                "BetLog:*" + pad9 + "*",
                "MaryLog:*" + pad9 + "*")) {
            doomed.addAll(scanKeys(redis, pattern));
        }
        doomed.add(RedisKeyContract.normalIndex(gameId, 1));
        doomed.add(RedisKeyContract.normalIndex(gameId, 5));
        doomed.add(RedisKeyContract.specialIndex(gameId, 1));
        doomed.add(RedisKeyContract.specialIndex(gameId, 5));
        doomed.add("PerKeyList_" + pad9);
        doomed.add("MaryKeyList_" + pad9);
        doomed.add("PerKeyList_" + pad9 + ":BL005");
        doomed.add("MaryKeyList_" + pad9 + ":BL005");
        int deleted = 0;
        List<String> batch = new ArrayList<>();
        for (String key : doomed) {
            if (key == null || key.isBlank()) continue;
            batch.add(key);
            if (batch.size() >= 200) {
                deleted += deleteKeys(redis, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) deleted += deleteKeys(redis, batch);
        System.out.printf("CLEARED_EXISTING_KEYS gameId=%d scanned=%d deleted=%d%n", gameId, doomed.size(), deleted);
    }

    private static List<String> scanKeys(RedisConnection redis, String pattern) throws IOException {
        List<String> keys = new ArrayList<>();
        String cursor = "0";
        int rounds = 0;
        do {
            Object raw = redis.command("SCAN", cursor, "MATCH", pattern, "COUNT", "400");
            if (!(raw instanceof List<?> pair) || pair.size() != 2) break;
            cursor = String.valueOf(pair.get(0));
            if (pair.get(1) instanceof List<?> members) {
                for (Object member : members) {
                    if (member != null && !member.toString().isBlank()) keys.add(member.toString());
                }
            }
        } while (!"0".equals(cursor) && ++rounds < 200);
        return keys;
    }

    private static int deleteKeys(RedisConnection redis, List<String> keys) throws IOException {
        if (keys.isEmpty()) return 0;
        String[] args = new String[keys.size() + 1];
        args[0] = "DEL";
        for (int i = 0; i < keys.size(); i++) args[i + 1] = keys.get(i);
        Object reply = redis.command(args);
        return reply instanceof Long count ? count.intValue() : keys.size();
    }

    private static void verifyPools(RedisConnection redis, long gameId) throws IOException {
        for (int profile : new int[]{1, 5}) {
            String ordinary = RedisKeyContract.normalIndex(gameId, profile);
            String mary = RedisKeyContract.specialIndex(gameId, profile);
            long ordinaryCard = asLong(redis.command("ZCARD", ordinary));
            long maryCard = asLong(redis.command("ZCARD", mary));
            if (ordinaryCard <= 0) throw new IllegalStateException("普通索引为空: " + ordinary);
            if (maryCard <= 0) throw new IllegalStateException("玛丽索引为空: " + mary);
            System.out.printf("REDIS_POOL %s zcard=%d %s zcard=%d%n", ordinary, ordinaryCard, mary, maryCard);
        }
        List<String> leftover = scanKeys(redis, "*:BL005");
        leftover.removeIf(key -> !key.contains(String.format(Locale.ROOT, "%08d", gameId))
                && !key.contains(String.format(Locale.ROOT, "%09d", gameId)));
        if (!leftover.isEmpty()) {
            throw new IllegalStateException("仍残留旧 BL005 key: " + leftover);
        }
    }

    private static long asLong(Object value) {
        if (value instanceof Long number) return number;
        if (value == null) return 0L;
        return Long.parseLong(value.toString());
    }

    /** 每个批次只使用一个 MULTI/EXEC，内部依次执行 zadd、rpush、ltrim。 */
    private static void flush(RedisConnection redis, List<Member> pending, LoaderConfig config,
                              Counters counters) throws IOException {
        if (pending.isEmpty()) return;
        List<String[]> commands = new ArrayList<>(pending.size() * 3 + 2);
        commands.add(new String[]{"MULTI"});
        for (Member member : pending) {
            String index = member.special()
                    ? RedisKeyContract.specialIndex(config.redisGameId(), member.betProfile())
                    : RedisKeyContract.normalIndex(config.redisGameId(), member.betProfile());
            String list = member.special()
                    ? RedisKeyContract.specialList(config.redisGameId(), member.betProfile(), member.multiplier())
                    : RedisKeyContract.normalList(config.redisGameId(), member.betProfile(), member.multiplier());
            commands.add(new String[]{"ZADD", index, Integer.toString(member.multiplier()),
                    Integer.toString(member.multiplier())});
            commands.add(new String[]{"RPUSH", list, member.payload()});
            int cap = member.special() ? config.outputLimits().specialCap : config.maxMembersPerMultiplier();
            commands.add(new String[]{"LTRIM", list, "-" + cap, "-1"});
        }
        commands.add(new String[]{"EXEC"});
        redis.exec(commands);
        counters.batches++;
        counters.loaded += pending.size();
        pending.clear();
    }

    public record RunResult(int sourceGameId, long redisGameId, String rulesHash,
                            int normalMembers, int specialMembers, int writtenMembers, int batches,
                            int maxConsecutiveWinsObserved, long normalLossMembers, long skippedByWeight,
                            long skippedOverLimit, int normalMultiplierBuckets, int specialMultiplierBuckets) { }

    private record Member(int betProfile, boolean special, int multiplier, String payload) { }

    private static final class Counters {
        int normalMembers;
        int specialMembers;
        int loaded;
        int batches;
        int maxConsecutiveWins;
        long attempts;
        long normalLossMembers;
        long skippedByWeight;
        long skippedOverLimit;
        final int[] normalByProfile = new int[6];
        final int[] specialByProfile = new int[6];
        final int[] lossByProfile = new int[6];
        final Set<Integer> normalMultipliers = new LinkedHashSet<>();
        final Set<Integer> specialMultipliers = new LinkedHashSet<>();
    }

    public record LoaderConfig(String host, int port, String username, String password, int database,
                               boolean ssl, int connectTimeoutMs, int socketTimeoutMs, long redisGameId,
                               int normalCount, int specialCount, int batchSize, int maxMembersPerMultiplier,
                               int maxConsecutiveWins, int normalMaxWinMultiplier, int specialMaxWinMultiplier,
                               Map<String, Integer> outcomeWeights,
                               Map<String, Integer> jointStateWeights, LoaderLimits outputLimits) {
        public LoaderConfig(String host, int port, String username, String password, int database,
                               boolean ssl, int connectTimeoutMs, int socketTimeoutMs, long redisGameId,
                               int normalCount, int specialCount, int batchSize, int maxMembersPerMultiplier,
                               int maxConsecutiveWins, int normalMaxWinMultiplier, int specialMaxWinMultiplier,
                               Map<String, Integer> outcomeWeights,
                               Map<String, Integer> jointStateWeights) { this(host, port, username, password, database, ssl, connectTimeoutMs, socketTimeoutMs, redisGameId, normalCount, specialCount, batchSize, maxMembersPerMultiplier, maxConsecutiveWins, normalMaxWinMultiplier, specialMaxWinMultiplier, outcomeWeights, jointStateWeights, new LoaderLimits(new java.util.Properties())); }

        private static final Set<String> COMMON_KEYS = Set.of(
                "redis.host", "redis.port", "redis.username", "redis.password", "redis.database",
                "redis.ssl", "redis.connect-timeout-ms", "redis.socket-timeout-ms", "redis.game-id",
                "generation.normal-count", "generation.special-count", "generation.batch-size",
                "generation.max-members-per-multiplier", "generation.special-max-members-per-multiplier",
                "generation.max-consecutive-wins",
                "generation.normal-min-win-multiplier", "generation.normal-max-win-multiplier",
                "generation.special-min-win-multiplier", "generation.special-max-win-multiplier");

        public static LoaderConfig load(Path file) throws IOException {
            if (!Files.isRegularFile(file)) throw new IllegalArgumentException("找不到 generator.properties: " + file);
            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); LoaderLimits.checkKeys(properties); }
            Set<String> allowed = new LinkedHashSet<>(COMMON_KEYS);
            for (String id : GameRuleCore.defaultJointStateWeights().keySet()) {
                allowed.add("generation.symbol.joint." + id + ".weight");
            }
            for (String id : GameRuleCore.defaultOutcomeWeights().keySet()) allowed.add(outcomePropertyKey(id));
            for (String key : properties.stringPropertyNames()) {
                String lower = key.toLowerCase();
                if (lower.contains("seed")) {
                    throw new IllegalArgumentException("正式配置含禁止参数: " + key);
                }
                if (!allowed.contains(key)) throw new IllegalArgumentException("正式配置包含未读取参数: " + key);
            }
            for (String key : allowed) {
                if (!properties.containsKey(key)) throw new IllegalArgumentException("正式配置缺少参数: " + key);
            }

            Map<String, Integer> outcomeWeights = new LinkedHashMap<>();
            for (String id : GameRuleCore.defaultOutcomeWeights().keySet()) {
                outcomeWeights.put(id, positive(properties, outcomePropertyKey(id)));
            }
            Map<String, Integer> jointStateWeights = new LinkedHashMap<>();
            for (String id : GameRuleCore.defaultJointStateWeights().keySet()) {
                jointStateWeights.put(id, positive(properties, "generation.symbol.joint." + id + ".weight"));
            }
            LoaderConfig config = new LoaderConfig(required(properties, "redis.host"),
                    integer(properties, "redis.port"), properties.getProperty("redis.username", "").trim(),
                    properties.getProperty("redis.password", ""), integer(properties, "redis.database"),
                    bool(properties, "redis.ssl"), positive(properties, "redis.connect-timeout-ms"),
                    positive(properties, "redis.socket-timeout-ms"), longValue(properties, "redis.game-id"),
                    nonNegative(properties, "generation.normal-count"),
                    nonNegative(properties, "generation.special-count"),
                    positive(properties, "generation.batch-size"),
                    positive(properties, "generation.max-members-per-multiplier"),
                    positive(properties, "generation.max-consecutive-wins"),
                    positive(properties, "generation.normal-max-win-multiplier"),
                    positive(properties, "generation.special-max-win-multiplier"),
                    Map.copyOf(outcomeWeights), Map.copyOf(jointStateWeights), new LoaderLimits(properties));
            config.validate();
            return config;
        }

        private static String outcomePropertyKey(String id) {
            return "generation.outcome." + id.toLowerCase(java.util.Locale.ROOT).replace('_', '-') + ".weight";
        }

        private void validate() {
            if (port < 1 || port > 65535 || database < 0 || database > 15 || redisGameId <= 0
                    || normalCount > MAX_TARGET || specialCount > MAX_TARGET
                    || normalCount + (long) specialCount == 0 || batchSize > 10_000
                    || maxConsecutiveWins > 2 || maxMembersPerMultiplier > 1_000_000) {
                throw new IllegalArgumentException("generator.properties 参数超出允许范围");
            }
        }

        private static String required(Properties properties, String key) {
            String value = properties.getProperty(key);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("配置不能为空: " + key);
            return value.trim();
        }

        private static int integer(Properties properties, String key) {
            return Integer.parseInt(properties.getProperty(key).trim());
        }

        private static int positive(Properties properties, String key) {
            int value = integer(properties, key);
            if (value <= 0) throw new IllegalArgumentException(key + " 必须为正整数");
            return value;
        }

        private static int nonNegative(Properties properties, String key) {
            int value = integer(properties, key);
            if (value < 0) throw new IllegalArgumentException(key + " 不能为负数");
            return value;
        }

        private static long longValue(Properties properties, String key) {
            return Long.parseLong(properties.getProperty(key).trim());
        }

        private static boolean bool(Properties properties, String key) {
            String value = properties.getProperty(key).trim();
            if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException(key + " 必须为 true 或 false");
            }
            return Boolean.parseBoolean(value);
        }
    }

    /** 最小 RESP2 客户端；最终可执行 JAR 不依赖外部 Redis 库。 */
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
            Socket socket = config.ssl() ? SSLSocketFactory.getDefault().createSocket() : new Socket();
            socket.connect(new InetSocketAddress(config.host(), config.port()), config.connectTimeoutMs());
            socket.setSoTimeout(config.socketTimeoutMs());
            RedisConnection connection = new RedisConnection(socket);
            try {
                if (!config.password().isBlank()) {
                    if (config.username().isBlank()) connection.command("AUTH", config.password());
                    else connection.command("AUTH", config.username(), config.password());
                }
                if (config.database() != 0) connection.command("SELECT", Integer.toString(config.database()));
                Object pong = connection.command("PING");
                if (!"PONG".equals(pong)) throw new IOException("Redis PING 返回异常");
                return connection;
            } catch (Exception error) {
                connection.close();
                if (error instanceof IOException io) throw io;
                throw new IOException("Redis 连接初始化失败", error);
            }
        }

        Object command(String... args) throws IOException {
            write(args);
            output.flush();
            return read();
        }

        List<Object> exec(List<String[]> commands) throws IOException {
            for (String[] command : commands) write(command);
            output.flush();
            List<Object> replies = new ArrayList<>(commands.size());
            for (int i = 0; i < commands.size(); i++) replies.add(read());
            Object executed = replies.get(replies.size() - 1);
            if (!(executed instanceof List<?> values) || values.size() != commands.size() - 2) {
                throw new IOException("Redis EXEC 返回数量与事务命令不一致");
            }
            return replies;
        }

        private void write(String[] args) throws IOException {
            output.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            for (String arg : args) {
                byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
                output.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
                output.write(bytes);
                output.write('\r');
                output.write('\n');
            }
        }

        private Object read() throws IOException {
            int prefix = input.read();
            if (prefix < 0) throw new EOFException("Redis 已关闭连接");
            return switch (prefix) {
                case '+' -> line();
                case '-' -> throw new IOException("Redis 错误: " + line());
                case ':' -> Long.parseLong(line());
                case '$' -> bulk();
                case '*' -> array();
                default -> throw new IOException("非法 RESP 前缀: " + (char) prefix);
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

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
