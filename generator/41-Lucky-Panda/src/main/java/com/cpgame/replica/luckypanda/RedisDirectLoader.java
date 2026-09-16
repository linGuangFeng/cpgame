package com.cpgame.replica.luckypanda;

import com.hd.pg.appapi.business.vo.cpgame.luckypanda.GameRuleCore;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaResultUtil;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.LuckyPandaSymbol;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.RoundClass;
import com.hd.pg.appapi.business.vo.cpgame.luckypanda.WeightScene;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.net.ssl.SSLSocketFactory;

/** Complete-Round generator that commits each configured batch directly to Redis. */
public final class RedisDirectLoader {
    static final int ENTRY_SWITCH_EVERY = 25;
    static final int SPECIAL_TRIGGER_WEIGHT_MULTIPLIER = 10;
    private static final int SCAT_INDEX = LuckyPandaSymbol.SCAT.ordinal();

    private RedisDirectLoader() { }

    public static void main(String[] args) {
        try {
            if (args.length > 1) {
                throw new IllegalArgumentException("usage: java -jar lucky-panda-redis-loader.jar [generator.properties]");
            }
            Path config = Path.of(args.length == 0 ? "generator.properties" : args[0]).toAbsolutePath().normalize();
            LoadSummary summary = run(config);
            System.out.printf("LOAD_COMPLETE redisGameId=%d normal=%d special=%d ordinaryLoss=%d ordinaryWin=%d batches=%d rulesVersion=%s rulesHash=%s%n",
                    summary.redisGameId(), summary.normalMembers(), summary.specialMembers(),
                    summary.ordinaryLoss(), summary.ordinaryWin(), summary.batches(),
                    LuckyPandaRulesMetadata.VERSION, LuckyPandaRulesMetadata.HASH);
        } catch (Exception ex) {
            System.err.println("[失败] " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            System.exit(1);
        }
    }

    public static LoadSummary run(Path configFile) throws Exception {
        LoaderConfig config = LoaderConfig.load(configFile);
        CompleteRoundFactory factory = new CompleteRoundFactory(config.betSize(), config.betLevel());
        CompleteRoundCodec codec = new CompleteRoundCodec();
        SecureRandom random = new SecureRandom();
        List<Member> pending = new ArrayList<>(config.batchSize());
        Counters counters = new Counters();
        try (RedisConnection redis = RedisConnection.connect(config)) {
            generateNaturally(config, factory, codec, random, redis, pending, counters);
            flush(redis, pending, config, counters);
        }
        return new LoadSummary(config.redisGameId(), counters.normalMembers, counters.specialMembers,
                counters.ordinaryLoss, counters.ordinaryWin, counters.batches);
    }

    private static void generateNaturally(LoaderConfig config, CompleteRoundFactory factory,
                                          CompleteRoundCodec codec, SecureRandom random,
                                          RedisConnection redis, List<Member> pending,
                                          Counters counters) throws Exception {
        Map<Integer, Integer> normalPerRatio = new HashMap<>();
        Map<Integer, Integer> specialPerRatio = new HashMap<>();
        Map<WeightScene, int[]> ordinary = config.weights();
        int drawsInEntry = 0;
        boolean specialEntryMode = false;
        boolean seededLoss = false;
        long attempts = 0;
        long attemptLimit = Math.max(100_000L, ((long) config.normalCount() + config.specialCount()) * 10_000L);
        while (counters.normalMembers < config.normalCount() || counters.specialMembers < config.specialCount()) {
            Member selected;
            while (true) {
                if (++attempts > attemptLimit) throw new IllegalStateException("配置范围/权重/容量内无法完成生成目标，已达到候选上限");
                if (drawsInEntry >= ENTRY_SWITCH_EVERY) {
                    specialEntryMode = !specialEntryMode;
                    drawsInEntry = 0;
                }
                if (counters.specialMembers >= config.specialCount()) specialEntryMode = false;
                if (counters.normalMembers >= config.normalCount()) specialEntryMode = true;
                boolean forceLoss = config.normalMinWinMultiplier() == 0 && !specialEntryMode && config.normalCount() > 0
                        && (!seededLoss || random.nextInt(1012 + 184) < 1012);
                CompleteRoundFactory.GeneratedRound generated;
                try {
                    generated = factory.generate(random, config.maxConsecutiveWins(), config.maxMarySpins(),
                            ordinary, forceLoss, specialEntryMode);
                } catch (CompleteRoundFactory.RoundRejectedException rejected) {
                    counters.skippedOverlongRounds++;
                    drawsInEntry++;
                    continue;
                }
                if (forceLoss) seededLoss = true;
                drawsInEntry++;
                RoundVerification verification = codec.verify(codec.encode(generated.fact()),
                        config.maxConsecutiveWins());
                boolean special = verification.special();
                if ((special && counters.specialMembers >= config.specialCount())
                        || (!special && counters.normalMembers >= config.normalCount())) {
                    continue;
                }
                int ratio = verification.actualMultiplier();
                int minWin = special ? config.maryMinWinMultiplier() : config.normalMinWinMultiplier();
                int maxWin = special ? config.maryMaxWinMultiplier() : config.normalMaxWinMultiplier();
                if (ratio < minWin || ratio > maxWin) {
                    counters.skippedOverMaxMultiplierRounds++;
                    continue;
                }
                Map<Integer, Integer> generatedPerRatio = special ? specialPerRatio : normalPerRatio;
                int memberCap = special ? config.specialMaxMembersPerMultiplier() : config.maxMembersPerMultiplier();
                generatedPerRatio.merge(ratio, 1, Integer::sum);
                if (verification.actualMultiplier() != generated.actualMultiplier()) {
                    throw new IllegalStateException("codec multiplier mismatch");
                }
                selected = new Member(special, ratio, codec.encode(generated.fact()), verification.roundClass());
                if (special && fiveBlockTrigger(generated.fact())) counters.fiveBlockFree++;
                break;
            }
            pending.add(selected);
            if (selected.special()) counters.specialMembers++;
            else counters.normalMembers++;
            if (selected.roundClass() == RoundClass.ORDINARY_LOSS) counters.ordinaryLoss++;
            if (selected.roundClass() == RoundClass.ORDINARY_WIN) counters.ordinaryWin++;
            if (pending.size() >= config.batchSize()) flush(redis, pending, config, counters);
        }
        System.out.printf("NATURAL_CLASSIFICATION_COMPLETE normal=%d special=%d ordinaryLoss=%d ordinaryWin=%d normalBuckets=%d specialBuckets=%d capPerMultiplier=%d discardedOverlong=%d%n",
                counters.normalMembers, counters.specialMembers, counters.ordinaryLoss, counters.ordinaryWin,
                normalPerRatio.size(), specialPerRatio.size(),
                config.maxMembersPerMultiplier(), counters.skippedOverlongRounds);
    }

    /**
     * Help awards 12 free spins for 5 Scat blocks. Capture had 1/38; with max-mary-spins=10
     * those rounds were discarded. Guarantee at least one such member in the special pool.
     */
    private static void ensureFiveBlockFree(LoaderConfig config, CompleteRoundFactory factory,
                                            CompleteRoundCodec codec, SecureRandom random,
                                            RedisConnection redis, List<Member> pending,
                                            Counters counters) throws Exception {
        if (config.specialCount() <= 0 || counters.fiveBlockFree > 0) return;
        Map<WeightScene, int[]> specialEntry = boostedScatter(config.weights());
        int[] paid = specialEntry.get(WeightScene.PAID_START);
        paid[SCAT_INDEX] = Math.multiplyExact(paid[SCAT_INDEX], 3);
        for (int attempt = 0; attempt < 800 && counters.fiveBlockFree == 0; attempt++) {
            CompleteRoundFactory.GeneratedRound generated;
            try {
                generated = factory.generate(random, config.maxConsecutiveWins(), config.maxMarySpins(),
                        specialEntry, false);
            } catch (CompleteRoundFactory.RoundRejectedException rejected) {
                continue;
            }
            RoundVerification verification = codec.verify(codec.encode(generated.fact()),
                    config.maxConsecutiveWins());
            if (!verification.special() || !fiveBlockTrigger(generated.fact())) continue;
            int ratio = verification.actualMultiplier();
            if (ratio < config.maryMinWinMultiplier() || ratio > config.maryMaxWinMultiplier()) continue;
            pending.add(new Member(true, ratio, codec.encode(generated.fact()), verification.roundClass()));
            counters.specialMembers++;
            counters.fiveBlockFree++;
            System.out.printf("FIVE_BLOCK_FREE_SEEDED multiplier=%d freeSpins=%d%n",
                    ratio, generated.fact().freeSpins().size());
            if (pending.size() >= config.batchSize()) flush(redis, pending, config, counters);
            return;
        }
        throw new IllegalStateException("unable to seed a 5-block scatter-free member (fsn=12)");
    }

    private static boolean fiveBlockTrigger(CompleteRoundFact fact) {
        if (fact.freeSpins().isEmpty()) return false;
        CompleteRoundFact.PageFact terminal = fact.paid().get(fact.paid().size() - 1);
        return terminal.board().scatterTokens() >= 5;
    }

    /** 特殊入口把付费首局 Scatter *10，但每列出现第一个触发符号后恢复原权重。连消/免费仍用原表。 */
    static Map<WeightScene, int[]> boostedScatter(Map<WeightScene, int[]> ordinary) {
        EnumMap<WeightScene, int[]> boosted = new EnumMap<>(WeightScene.class);
        for (WeightScene scene : WeightScene.values()) {
            int[] table = ordinary.get(scene).clone();
            if (scene == WeightScene.PAID_START) {
                table[SCAT_INDEX] = Math.multiplyExact(table[SCAT_INDEX], SPECIAL_TRIGGER_WEIGHT_MULTIPLIER);
            }
            boosted.put(scene, table);
        }
        return boosted;
    }

    static boolean tryReserveMultiplier(Map<Integer, Integer> generatedPerRatio, int ratio, int cap) {
        int current = generatedPerRatio.getOrDefault(ratio, 0);
        if (current >= cap) return false;
        generatedPerRatio.put(ratio, current + 1);
        return true;
    }

    private static void flush(RedisConnection redis, List<Member> pending, LoaderConfig config, Counters counters)
            throws IOException {
        if (pending.isEmpty()) return;
        List<String[]> commands = new ArrayList<>(pending.size() * 3 + 2);
        commands.add(new String[]{"MULTI"});
        for (Member member : pending) {
            String index = member.special()
                    ? RedisKeyContract.specialIndex(config.redisGameId())
                    : RedisKeyContract.normalIndex(config.redisGameId());
            String list = member.special()
                    ? RedisKeyContract.specialList(config.redisGameId(), member.ratio())
                    : RedisKeyContract.normalList(config.redisGameId(), member.ratio());
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

    public record LoadSummary(long redisGameId, int normalMembers, int specialMembers,
                              int ordinaryLoss, int ordinaryWin, int batches) { }

    private record Member(boolean special, int ratio, String payload, RoundClass roundClass) { }

    private static final class Counters {
        int batches, loaded, normalMembers, specialMembers, ordinaryLoss, ordinaryWin;
        int skippedCappedRounds, skippedOverlongRounds, skippedOverMaxMultiplierRounds, fiveBlockFree;
    }

    record LoaderConfig(String host, int port, String username, String password, int database, boolean ssl,
                        int connectTimeoutMs, int socketTimeoutMs, long redisGameId,
                        BigDecimal betSize, int betLevel,
                        int normalCount, int specialCount, int batchSize, int maxConsecutiveWins,
                        int maxMarySpins, int maxMembersPerMultiplier, int specialMaxMembersPerMultiplier,
                        int normalMinWinMultiplier, int normalMaxWinMultiplier,
                        int maryMinWinMultiplier, int maryMaxWinMultiplier,
                        Map<WeightScene, int[]> weights) {
        static LoaderConfig load(Path file) throws IOException {
            if (!Files.isRegularFile(file)) throw new IllegalArgumentException("config file not found: " + file);
            Properties p = new Properties();
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(reader); LoaderLimits.checkKeys(p); }
            EnumMap<WeightScene, int[]> weights = new EnumMap<>(WeightScene.class);
            weights.put(WeightScene.PAID_START, requiredWeights(p, "paid-start"));
            weights.put(WeightScene.CASCADE_REFILL, requiredWeights(p, "cascade-refill"));
            weights.put(WeightScene.FREE_START, requiredWeights(p, "free-start"));
            weights.put(WeightScene.FREE_CASCADE_REFILL, requiredWeights(p, "free-cascade-refill"));
            LoaderConfig c = new LoaderConfig(
                    required(p, "redis.host"),
                    integer(p, "redis.port"),
                    p.getProperty("redis.username", "").trim(),
                    p.getProperty("redis.password", ""),
                    integer(p, "redis.database"),
                    bool(p, "redis.ssl"),
                    integer(p, "redis.connect-timeout-ms"),
                    integer(p, "redis.socket-timeout-ms"),
                    Long.parseLong(required(p, "redis.game-id")),
                    new BigDecimal(required(p, "generation.bet-size")),
                    integer(p, "generation.bet-level"),
                    integer(p, "generation.normal-count"),
                    integer(p, "generation.special-count"),
                    integer(p, "generation.batch-size"),
                    integer(p, "generation.max-consecutive-wins"),
                    integer(p, "generation.max-mary-spins"),
                    integer(p, "generation.max-members-per-multiplier"),
                    integer(p, "generation.special-max-members-per-multiplier"),
                    integer(p, "generation.normal-min-win-multiplier"),
                    integer(p, "generation.normal-max-win-multiplier"),
                    integer(p, "generation.mary-min-win-multiplier"),
                    integer(p, "generation.mary-max-win-multiplier"),
                    weights);
            if (c.port < 1 || c.port > 65535 || c.database < 0 || c.redisGameId <= 0
                    || c.betSize.signum() <= 0 || c.betLevel < 1
                    || c.normalCount < 0 || c.specialCount < 0 || c.normalCount + c.specialCount == 0
                    || c.batchSize < 1 || c.batchSize > 10_000 || c.maxConsecutiveWins < 1
                    || c.maxMarySpins < LuckyPandaResultUtil.scatterFreeAwarded(GameRuleCore.SCAT_TOTAL_MAX_BLOCKS)
                    || c.normalMinWinMultiplier < 0 || c.maryMinWinMultiplier < 0
                    || c.normalMaxWinMultiplier < 0 || c.maryMaxWinMultiplier < 0
                    || c.normalMinWinMultiplier > c.normalMaxWinMultiplier
                    || c.maryMinWinMultiplier > c.maryMaxWinMultiplier
                    || c.maxMembersPerMultiplier < 1 || c.specialMaxMembersPerMultiplier < 1) {
                throw new IllegalArgumentException("invalid generator.properties values");
            }
            return c;
        }

        private static String required(Properties p, String key) {
            String value = p.getProperty(key);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("missing config: " + key);
            return value.trim();
        }

        private static int integer(Properties p, String key) {
            return Integer.parseInt(required(p, key));
        }

        private static boolean bool(Properties p, String key) {
            return Boolean.parseBoolean(required(p, key));
        }

        private static int[] requiredWeights(Properties p, String scene) {
            LuckyPandaSymbol[] symbols = LuckyPandaSymbol.values();
            int[] values = new int[symbols.length];
            for (int i = 0; i < symbols.length; i++) {
                values[i] = integer(p, "generation.symbol." + symbols[i].wireName() + "." + scene + "-weight");
            }
            return values;
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
            } catch (ConnectException refused) {
                throw new IOException("无法连接 Redis %s:%d（Connection refused）。只检查网络和 generator.properties 的 redis.host / redis.port，不要改出牌规则。"
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
                throw new IOException("Redis connection initialization failed", ex);
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
                byte[] bytes = arg.getBytes(StandardCharsets.US_ASCII);
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
