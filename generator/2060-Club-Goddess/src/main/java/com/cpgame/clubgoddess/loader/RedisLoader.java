package com.cpgame.clubgoddess.loader;

import com.cpgame.clubgoddess.codec.MinimalRoundFactCodec;
import com.cpgame.clubgoddess.core.GameModels.ResultAnalysis;
import com.cpgame.clubgoddess.core.GameModels.RoundBundle;
import com.cpgame.clubgoddess.core.GameRuleCore;
import com.cpgame.clubgoddess.core.ResultUtil;
import com.cpgame.clubgoddess.core.RoundVerifier;
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
import javax.net.ssl.SSLSocketFactory;

/** SecureRandom 带权自然生成完整 Round，经独立 ResultUtil 反推后直接写入平台 Redis。 */
public final class RedisLoader {
    private static final BigDecimal BET = new BigDecimal("0.01");
    private static final int LEVEL = 10;
    private static final BigDecimal VERIFY_BALANCE = new BigDecimal("1000000");

    private RedisLoader() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("用法：java -jar club-goddess-redis-loader.jar [generator.properties]");
        Path configFile = Path.of(args.length == 0 ? "generator.properties" : args[0])
                .toAbsolutePath().normalize();
        LoadSummary summary = run(configFile);
        System.out.printf(Locale.ROOT,
                "生成完成 redisGameId=%d 普通生成=%d 特殊生成=%d 普通写入=%d 特殊写入=%d 0倍跳过=%d 批次=%d rulesHash=%s%n",
                summary.redisGameId(), summary.normalGenerated(), summary.specialGenerated(),
                summary.normalWritten(), summary.specialWritten(), summary.zeroSkipped(), summary.batches(),
                GameRuleCore.RULES_HASH);
    }

    public static LoadSummary run(Path configFile) throws Exception {
        LoaderConfig config = LoaderConfig.load(configFile);
        GameRuleCore core = new GameRuleCore();
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec();
        List<Member> pending = new ArrayList<>(config.batchSize());
        Counters counters = new Counters();
        try (RedisConnection redis = RedisConnection.connect(config)) {
            while (counters.normalWritten < config.normalCount()) {
                RoundBundle round = core.generateOrdinaryRound(BET, LEVEL, VERIFY_BALANCE);
                counters.normalGenerated++;
                LoaderLimits.checkAttempts(counters.normalGenerated, config.normalCount());
                ResultAnalysis analysis = RoundVerifier.verify(round);
                if (analysis.hasContinuation() || !analysis.terminal()) {
                    throw new IllegalStateException("只允许写入已复核的完整终止 Round");
                }
                int integerMultiplier = ResultUtil.integerMultiplier(round);
                BigDecimal multiplier = BigDecimal.valueOf(integerMultiplier);
                if (!config.outputLimits().accepts(false, multiplier) || multiplier.compareTo(config.normalMaxWinMultiplier()) > 0) {
                    counters.overLimitSkipped++;
                    continue;
                }
                int consecutiveWins = integerMultiplier > 0 ? 1 : 0;
                if (consecutiveWins > config.maxConsecutiveWins()) {
                    counters.overLimitSkipped++;
                    continue;
                }
                String member = codec.encode(round);
                RoundBundle decoded = codec.verify(member);
                RoundVerifier.verify(decoded);
                if (ResultUtil.integerMultiplier(decoded) != integerMultiplier
                        || !decoded.roundKey().equals(round.roundKey())) {
                    throw new IllegalStateException("Redis member 往返后的倍率或 roundKey 不一致");
                }
                String ratio = multiplier(multiplier);
                pending.add(new Member(false, ratio, member));
                counters.normalWritten++;
                counters.normalDistribution.merge(ratio, 1, Integer::sum);
                if (pending.size() >= config.batchSize()) flush(redis, pending, config, counters);
            }
            while (counters.specialWritten < config.specialCount()) {
                RoundBundle round=core.generateSpecialRound(BET,LEVEL,VERIFY_BALANCE);
                counters.specialGenerated++;
                LoaderLimits.checkAttempts(counters.specialGenerated, config.specialCount());
                RoundVerifier.verify(round);
                int integerMultiplier=ResultUtil.integerMultiplier(round);
                if (!config.outputLimits().accepts(true, integerMultiplier) || BigDecimal.valueOf(integerMultiplier).compareTo(config.specialMaxWinMultiplier())>0) {
                    counters.overLimitSkipped++; continue;
                }
                String member=codec.encode(round);RoundBundle decoded=codec.verify(member);
                if(ResultUtil.integerMultiplier(decoded)!=integerMultiplier)throw new IllegalStateException("special multiplier mismatch");
                String ratio=Integer.toString(integerMultiplier);pending.add(new Member(true,ratio,member));
                counters.specialWritten++;counters.specialDistribution.merge(ratio,1,Integer::sum);
                if(pending.size()>=config.batchSize())flush(redis,pending,config,counters);
            }
            flush(redis, pending, config, counters);
        }
        return counters.summary(config.redisGameId());
    }

    /** 每个批次在同一个 MULTI/EXEC 中同时维护倍率索引、列表和容量。 */
    private static void flush(RedisConnection redis, List<Member> pending, LoaderConfig config, Counters counters)
            throws IOException {
        if (pending.isEmpty()) return;
        List<String[]> commands = new ArrayList<>(pending.size() * 3 + 2);
        commands.add(new String[]{"MULTI"});
        for (Member member : pending) {
            String index = member.special() ? specialIndex(config.redisGameId()) : normalIndex(config.redisGameId());
            String list = member.special() ? specialList(config.redisGameId(), member.multiplier())
                    : normalList(config.redisGameId(), member.multiplier());
            commands.add(new String[]{"ZADD", index, member.multiplier(), member.multiplier()});
            commands.add(new String[]{"RPUSH", list, member.payload()});
            int cap = member.special() ? config.outputLimits().specialCap : config.maxMembersPerMultiplier();
            commands.add(new String[]{"LTRIM", list, "-" + cap, "-1"});
        }
        commands.add(new String[]{"EXEC"});
        redis.pipeline(commands);
        counters.batches++;
        System.out.printf(Locale.ROOT, "批次已提交 batch=%d members=%d%n", counters.batches, pending.size());
        pending.clear();
    }

    public static String normalIndex(long id) { return String.format(Locale.ROOT, "PerKeyList_%09d", id); }
    public static String specialIndex(long id) { return String.format(Locale.ROOT, "MaryKeyList_%09d", id); }
    public static String normalList(long id, String multiplier) {
        return String.format(Locale.ROOT, "BetLog:0%08d:%06d", id, integerRatio(multiplier));
    }
    public static String specialList(long id, String multiplier) {
        return String.format(Locale.ROOT, "MaryLog:%09d:%06d", id, integerRatio(multiplier));
    }
    public static String multiplier(BigDecimal value) { return Integer.toString(value.stripTrailingZeros().intValueExact()); }
    private static int integerRatio(String value) {
        return new BigDecimal(value).stripTrailingZeros().intValueExact();
    }

    public record LoadSummary(long redisGameId, int normalGenerated, int specialGenerated,
                              int normalWritten, int specialWritten, long zeroSkipped,
                              long overLimitSkipped, int batches,
                              Map<String, Integer> normalDistribution) { }
    private record Member(boolean special, String multiplier, String payload) { }
    private static final class Counters {
        int normalGenerated, normalWritten, specialGenerated, specialWritten, batches;
        long zeroSkipped, overLimitSkipped;
        final Map<String, Integer> normalDistribution = new LinkedHashMap<>();
        final Map<String, Integer> specialDistribution = new LinkedHashMap<>();
        LoadSummary summary(long gameId) {
            return new LoadSummary(gameId, normalGenerated, specialGenerated, normalWritten, specialWritten, zeroSkipped,
                    overLimitSkipped, batches, Map.copyOf(normalDistribution));
        }
    }

    public record LoaderConfig(String host, int port, String username, String password, int database, boolean ssl,
                               int connectTimeoutMs, int socketTimeoutMs, long redisGameId, int normalCount,
                               int specialCount, int batchSize, int maxMembersPerMultiplier,
                               int maxConsecutiveWins, BigDecimal normalMaxWinMultiplier,
                               BigDecimal specialMaxWinMultiplier, LoaderLimits outputLimits) {
        public LoaderConfig(String host, int port, String username, String password, int database, boolean ssl,
                               int connectTimeoutMs, int socketTimeoutMs, long redisGameId, int normalCount,
                               int specialCount, int batchSize, int maxMembersPerMultiplier,
                               int maxConsecutiveWins, BigDecimal normalMaxWinMultiplier,
                               BigDecimal specialMaxWinMultiplier) { this(host, port, username, password, database, ssl, connectTimeoutMs, socketTimeoutMs, redisGameId, normalCount, specialCount, batchSize, maxMembersPerMultiplier, maxConsecutiveWins, normalMaxWinMultiplier, specialMaxWinMultiplier, new LoaderLimits(new java.util.Properties())); }

        private static final int MAX_TARGET = Integer.MAX_VALUE;
        private static final Set<String> FIXED_KEYS = Set.of(
                "redis.host", "redis.port", "redis.username", "redis.password", "redis.database", "redis.ssl",
                "redis.connect-timeout-ms", "redis.socket-timeout-ms", "redis.game-id",
                "generation.normal-count", "generation.special-count", "generation.batch-size",
                "generation.max-members-per-multiplier", "generation.special-max-members-per-multiplier",
                "generation.max-consecutive-wins",
                "generation.normal-min-win-multiplier", "generation.normal-max-win-multiplier",
                "generation.special-min-win-multiplier", "generation.special-max-win-multiplier");

        public static LoaderConfig load(Path file) throws IOException {
            if (!Files.isRegularFile(file)) throw new IllegalArgumentException("配置文件不存在：" + file);
            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); LoaderLimits.checkKeys(properties); }
            return from(properties);
        }

        static LoaderConfig from(Properties p) {
            rejectUnknownProperties(p);
            LoaderConfig config = new LoaderConfig(required(p, "redis.host"), integer(p, "redis.port", 6379),
                    p.getProperty("redis.username", "").trim(), p.getProperty("redis.password", ""),
                    integer(p, "redis.database", 0), bool(p, "redis.ssl", false),
                    integer(p, "redis.connect-timeout-ms", 5000), integer(p, "redis.socket-timeout-ms", 30000),
                    longValue(p, "redis.game-id", 2060L), integer(p, "generation.normal-count", 100_000_000),
                    integer(p, "generation.special-count", 30), integer(p, "generation.batch-size", 100),
                    integer(p, "generation.max-members-per-multiplier", 300),
                    integer(p, "generation.max-consecutive-wins", 10),
                    decimal(p, "generation.normal-max-win-multiplier", "20000"),
                    decimal(p, "generation.special-max-win-multiplier", "20000"), new LoaderLimits(p));
            if (config.port() < 1 || config.port() > 65535 || config.database() < 0 || config.redisGameId() <= 0
                    || config.normalCount() < 1 || config.normalCount() > MAX_TARGET
                    || config.specialCount() < 0 || config.specialCount() > MAX_TARGET || config.batchSize() < 1 || config.batchSize() > 10_000
                    || config.maxMembersPerMultiplier() < 1 || config.maxConsecutiveWins() < 1
                    || config.normalMaxWinMultiplier().signum() <= 0 || config.specialMaxWinMultiplier().signum() <= 0
                    || config.connectTimeoutMs() < 1 || config.socketTimeoutMs() < 1) {
                throw new IllegalArgumentException("generator.properties 参数超出允许范围");
            }
            return config;
        }

        private static void rejectUnknownProperties(Properties p) {
            Set<String> allowed = new LinkedHashSet<>(FIXED_KEYS);
            for (String key : p.stringPropertyNames()) {
                if (!allowed.contains(key)) throw new IllegalArgumentException("非正式配置参数：" + key);
            }
        }
        private static String required(Properties p, String key) {
            String value = p.getProperty(key);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少配置：" + key);
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
        private static BigDecimal decimal(Properties p, String key, String fallback) {
            return new BigDecimal(p.getProperty(key, fallback).trim());
        }
    }

    /** 最小 RESP2 客户端，最终带依赖 JAR 不依赖本机 redis-cli。 */
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
                if (!"PONG".equals(pong)) throw new IOException("Redis PING 返回：" + pong);
                return connection;
            } catch (Exception error) {
                connection.close();
                if (error instanceof IOException io) throw io;
                throw new IOException("Redis 连接初始化失败", error);
            }
        }
        Object command(String... args) throws IOException { write(args); output.flush(); return read(); }
        List<Object> pipeline(List<String[]> commands) throws IOException {
            for (String[] command : commands) write(command);
            output.flush();
            List<Object> replies = new ArrayList<>(commands.size());
            for (int i = 0; i < commands.size(); i++) replies.add(read());
            Object exec = replies.get(replies.size() - 1);
            if (!(exec instanceof List<?> values) || values.size() != commands.size() - 2) {
                throw new IOException("Redis EXEC 返回数量不一致");
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
            if (prefix < 0) throw new EOFException("Redis 已关闭连接");
            return switch (prefix) {
                case '+' -> line();
                case '-' -> throw new IOException("Redis error: " + line());
                case ':' -> Long.parseLong(line());
                case '$' -> bulk();
                case '*' -> array();
                default -> throw new IOException("无效 RESP 前缀：" + (char) prefix);
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
