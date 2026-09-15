package com.cpgame.luckydragon.loader;

import com.cpgame.luckydragon.core.GameRound;
import com.cpgame.luckydragon.core.GameRuleCore;
import com.cpgame.luckydragon.core.IndependentRoundVerifier;
import com.cpgame.luckydragon.core.MinimalFactCodec;
import com.cpgame.luckydragon.core.OutcomeType;
import com.cpgame.luckydragon.core.RandomRoundGenerator;
import com.cpgame.luckydragon.core.ResultUtil;
import com.cpgame.luckydragon.core.RoundFacts;
import com.cpgame.luckydragon.core.RoundRequest;
import com.cpgame.luckydragon.core.SpinResult;

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
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** 正式入口：自然生成、独立解码复核，并以 MULTI/EXEC 原子填充消费者结果池。 */
public final class LoaderMain {
    private LoaderMain() { }

    public static void main(String[] args) {
        if (args.length > 1) { System.err.println("用法：java -jar lucky-dragon-redis-loader.jar [generator.properties]"); System.exit(2); }
        Path config = Path.of(args.length == 0 ? "generator.properties" : args[0]).toAbsolutePath().normalize();
        try {
            RunResult result = new RedisLoader().run(config);
            System.out.printf(Locale.ROOT, "[PASS] raw gid42 Redis loaded loss=%d normalWin=%d special=%d written=%d batches=%d buckets=%d/%d rulesHash=%s%n",
                result.lossMembers(), result.normalMembers(), result.specialMembers(), result.writtenMembers(), result.batches(),
                result.normalMultiplierBuckets(), result.specialMultiplierBuckets(), result.rulesHash());
        } catch (Exception error) { System.err.println("[FAIL] Redis Loader: " + error.getMessage()); System.exit(2); }
    }

    public record RunResult(long redisGameId, String rulesHash, int lossMembers, int normalMembers, int specialMembers,
                            int writtenMembers, int batches, long attempts, long skippedZero,
                            long skippedBucketFull, long skippedOverLimit, int normalMultiplierBuckets,
                            int specialMultiplierBuckets) { }

    public static final class RedisLoader {
        private static final int MAX_TARGET = Integer.MAX_VALUE;

        public RunResult run(Path configPath) throws Exception {
            LoaderConfig config = LoaderConfig.load(configPath);
            GameRuleCore core = new GameRuleCore();
            MinimalFactCodec codec = new MinimalFactCodec();
            IndependentRoundVerifier verifier = new IndependentRoundVerifier(core);
            RandomRoundGenerator generator = new RandomRoundGenerator(core, new SecureRandom(), config.jointStateModel());
            Counters counters = new Counters();
            List<Member> pending = new ArrayList<>(config.batchSize());
            try (RedisConnection redis = RedisConnection.connect(config)) {
                long maxAttempts = Math.max(100_000L, ((long) config.normalCount() + config.specialCount()) * 100_000L);
                while (counters.lossMembers < config.outputLimits().lossTarget(config.normalCount())
                    || counters.normalMembers < config.normalCount()
                    || counters.specialMembers < config.specialCount()) {
                    
                    if (++counters.attempts > maxAttempts) throw new IllegalStateException("配置目标在最大尝试次数内无法完成");
                    RoundRequest request = new RoundRequest(new BigDecimal("0.5"), 1);
                    SpinResult result = generator.next(request);
                    RoundFacts facts = new RoundFacts(request.betSize(), request.betLevel(), result.symbols(), result.reelMultiplier(),
                        "42-" + Long.toUnsignedString(counters.attempts), 0, true);
                    int multiplier = ResultUtil.positiveMultiplier(core, facts);
                    if (multiplier == 0) counters.consecutiveWins = 0;
                    else if (++counters.consecutiveWins > config.maxConsecutiveWins()) {
                        counters.skippedOverLimit++; continue;
                    }
                    boolean special = result.outcome() == OutcomeType.WILD_MULTIPLIER_X3
                        || result.outcome() == OutcomeType.WILD_MULTIPLIER_X5
                        || result.outcome() == OutcomeType.WILD_MULTIPLIER_X9;
                    if (special && counters.specialMembers >= config.specialCount()) continue;
                    if (!special && multiplier == 0 && counters.lossMembers >= config.normalCount()) continue;
                    if (!special && multiplier > 0 && counters.normalMembers >= config.normalCount()) continue;
                    int maximum = special ? config.specialMaxTotalMultiplier() : config.normalMaxTotalMultiplier();
                    if (!config.outputLimits().accepts(special, multiplier) || multiplier > maximum) { counters.skippedOverLimit++; continue; }
                    String bucket = (special ? "S:" : "N:") + multiplier;
                    GameRound round = new GameRound(facts.roundKey(), facts.roundKey().substring(3), request, result,
                        BigDecimal.ZERO, Instant.now(), 0, true);
                    verifier.verify(round);
                    byte[] member = codec.encode(round);
                    RoundFacts decoded = codec.decode(member);
                    SpinResult decodedResult = ResultUtil.analyze(core, decoded);
                    if (!decodedResult.equals(result) || !facts.equals(decoded)) throw new IllegalStateException("Redis member codec/verifier mismatch");
                    pending.add(new Member(special, multiplier, new String(member, StandardCharsets.US_ASCII)));
                    counters.bucketCounts.merge(bucket, 1, Integer::sum);
                    if (special) { counters.specialMembers++; counters.specialMultipliers.add(multiplier); }
                    else if (multiplier == 0) { counters.lossMembers++; counters.normalMultipliers.add(0); }
                    else { counters.normalMembers++; counters.normalMultipliers.add(multiplier); }
                    if (pending.size() >= config.batchSize()) flush(redis, pending, config, counters);
                }
                flush(redis, pending, config, counters);
            }
            return new RunResult(config.redisGameId(), GameRuleCore.RULES_HASH, counters.lossMembers, counters.normalMembers, counters.specialMembers,
                counters.written, counters.batches, counters.attempts, counters.skippedZero, counters.skippedBucketFull,
                counters.skippedOverLimit, counters.normalMultipliers.size(), counters.specialMultipliers.size());
        }

        private static boolean allReachableBucketsFull(LoaderConfig config, Counters counters) {
            boolean lossFull = counters.lossMembers >= config.normalCount()
                || counters.bucketCounts.getOrDefault("N:0", 0) >= config.maxMembersPerMultiplier();
            boolean normalFull = counters.normalMembers >= config.normalCount()
                || List.of(5, 21, 111, 1111).stream().allMatch(multiplier ->
                    counters.bucketCounts.getOrDefault("N:" + multiplier, 0) >= config.maxMembersPerMultiplier());
            List<Integer> special = new ArrayList<>();
            for (int base : List.of(5, 21, 111, 1111)) for (int rpx : List.of(3, 5, 9)) {
                int multiplier = base * rpx;
                if (multiplier <= config.specialMaxTotalMultiplier()) special.add(multiplier);
            }
            boolean specialFull = counters.specialMembers >= config.specialCount()
                || special.stream().allMatch(multiplier ->
                    counters.bucketCounts.getOrDefault("S:" + multiplier, 0) >= config.maxMembersPerMultiplier());
            return lossFull && normalFull && specialFull;
        }

        private static void flush(RedisConnection redis, List<Member> pending, LoaderConfig config, Counters counters) throws IOException {
            if (pending.isEmpty()) return;
            List<String[]> commands = new ArrayList<>(pending.size() * 3 + 2);
            commands.add(new String[]{"MULTI"});
            for (Member member : pending) {
                String index = member.special ? RedisKeyContract.specialIndex(config.redisGameId()) : RedisKeyContract.normalIndex(config.redisGameId());
                String list = member.special ? RedisKeyContract.specialList(config.redisGameId(), member.multiplier) : RedisKeyContract.normalList(config.redisGameId(), member.multiplier);
                commands.add(new String[]{"ZADD", index, Integer.toString(member.multiplier), Integer.toString(member.multiplier)});
                commands.add(new String[]{"RPUSH", list, member.payload});
                int cap = member.special ? config.outputLimits().specialCap : config.maxMembersPerMultiplier();
                commands.add(new String[]{"LTRIM", list, "-" + cap, "-1"});
            }
            commands.add(new String[]{"EXEC"});
            redis.exec(commands);
            counters.batches++; counters.written += pending.size(); pending.clear();
        }

        private record Member(boolean special, int multiplier, String payload) { }
        private static final class Counters {
            final Set<Integer> normalMultipliers = new LinkedHashSet<>();
            final Set<Integer> specialMultipliers = new LinkedHashSet<>();
            final Map<String,Integer> bucketCounts = new LinkedHashMap<>();
            int lossMembers, normalMembers, specialMembers, written, batches, consecutiveWins;
            long attempts, skippedZero, skippedBucketFull, skippedOverLimit;
        }
    }

    public record LoaderConfig(String host, int port, String username, String password, int database, boolean ssl,
                               int connectTimeoutMs, int socketTimeoutMs, long redisGameId, int normalCount,
                               int specialCount, int batchSize, int maxMembersPerMultiplier, int maxConsecutiveWins,
                               int normalMaxTotalMultiplier, int specialMaxTotalMultiplier,
                               Map<String,Integer> symbolWeights, Map<Integer,Integer> multiplierWeights,
                               String jointStateModel, LoaderLimits outputLimits) {
        public LoaderConfig(String host, int port, String username, String password, int database, boolean ssl,
                               int connectTimeoutMs, int socketTimeoutMs, long redisGameId, int normalCount,
                               int specialCount, int batchSize, int maxMembersPerMultiplier, int maxConsecutiveWins,
                               int normalMaxTotalMultiplier, int specialMaxTotalMultiplier,
                               Map<String,Integer> symbolWeights, Map<Integer,Integer> multiplierWeights,
                               String jointStateModel) { this(host, port, username, password, database, ssl, connectTimeoutMs, socketTimeoutMs, redisGameId, normalCount, specialCount, batchSize, maxMembersPerMultiplier, maxConsecutiveWins, normalMaxTotalMultiplier, specialMaxTotalMultiplier, symbolWeights, multiplierWeights, jointStateModel, new LoaderLimits(new java.util.Properties())); }

        private static final int MAX_TARGET = Integer.MAX_VALUE;
        private static final Set<String> REQUIRED = Set.of(
            "redis.host","redis.port","redis.username","redis.password","redis.database","redis.ssl",
            "redis.connect-timeout-ms","redis.socket-timeout-ms","redis.game-id",
            "generation.normal-count","generation.special-count","generation.batch-size",
            "generation.max-members-per-multiplier","generation.special-max-members-per-multiplier",
            "generation.max-consecutive-wins",
            "generation.normal-min-win-multiplier","generation.special-min-win-multiplier",
            "generation.normal-max-total-win-multiplier","generation.special-max-total-win-multiplier","generation.joint-state-model");

        public static LoaderConfig load(Path file) throws IOException {
            if (!Files.isRegularFile(file)) throw new IllegalArgumentException("找不到 generator.properties: " + file);
            Properties p = new Properties();
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(reader); LoaderLimits.checkKeys(p); }
            for (String key : p.stringPropertyNames()) {
                if (key.toLowerCase(Locale.ROOT).contains("seed")) throw new IllegalArgumentException("禁止 seed 配置");
                if (!REQUIRED.contains(key)) throw new IllegalArgumentException("未读取的配置键: " + key);
            }
            for (String key : REQUIRED) if (!p.containsKey(key)) throw new IllegalArgumentException("缺少配置键: " + key);
            Map<String,Integer> symbols = Map.of();
            Map<Integer,Integer> multipliers = Map.of();
            String jointModel = required(p,"generation.joint-state-model");
            if (RandomRoundGenerator.jointStateCount(jointModel) < 2) throw new IllegalArgumentException("joint state model requires multiple complete states");
            LoaderConfig c = new LoaderConfig(required(p,"redis.host"), integer(p,"redis.port"), p.getProperty("redis.username", ""), p.getProperty("redis.password", ""), integer(p,"redis.database"), bool(p,"redis.ssl"), positive(p,"redis.connect-timeout-ms"), positive(p,"redis.socket-timeout-ms"), longValue(p,"redis.game-id"), nonNegative(p,"generation.normal-count"), nonNegative(p,"generation.special-count"), positive(p,"generation.batch-size"), positive(p,"generation.max-members-per-multiplier"), positive(p,"generation.max-consecutive-wins"), positive(p,"generation.normal-max-total-win-multiplier"), positive(p,"generation.special-max-total-win-multiplier"), Map.copyOf(symbols), Map.copyOf(multipliers), jointModel, new LoaderLimits(p));
            if (c.redisGameId <= 0 || c.normalCount + c.specialCount == 0 || c.batchSize > 10_000 || c.maxConsecutiveWins > 10_000 || c.maxMembersPerMultiplier > 1_000_000 || c.normalCount > MAX_TARGET || c.specialCount > MAX_TARGET) throw new IllegalArgumentException("generator.properties 范围或 raw gid42 合同无效");
            return c;
        }
        private static String required(Properties p,String k){String v=p.getProperty(k);if(v==null||v.isBlank())throw new IllegalArgumentException("配置不能为空: "+k);return v.trim();}
        private static int integer(Properties p,String k){try{return Integer.parseInt(required(p,k));}catch(NumberFormatException e){throw new IllegalArgumentException("整数配置无效: "+k);}}
        private static int positive(Properties p,String k){int v=integer(p,k);if(v<=0)throw new IllegalArgumentException(k+" must be positive");return v;}
        private static int nonNegative(Properties p,String k){int v=integer(p,k);if(v<0)throw new IllegalArgumentException(k+" must be non-negative");return v;}
        private static long longValue(Properties p,String k){try{return Long.parseLong(required(p,k));}catch(NumberFormatException e){throw new IllegalArgumentException("long config invalid: "+k);}}
        private static boolean bool(Properties p,String k){String v=required(p,k);if(!v.equalsIgnoreCase("true")&&!v.equalsIgnoreCase("false"))throw new IllegalArgumentException(k+" must be boolean");return Boolean.parseBoolean(v);}
    }

    static final class RedisConnection implements AutoCloseable {
        private final Socket socket; private final InputStream input; private final OutputStream output;
        private RedisConnection(Socket socket) throws IOException { this.socket=socket; input=new BufferedInputStream(socket.getInputStream()); output=new BufferedOutputStream(socket.getOutputStream()); }
        static RedisConnection connect(LoaderConfig c) throws IOException { Socket s=c.ssl?SSLSocketFactory.getDefault().createSocket():new Socket(); s.connect(new InetSocketAddress(c.host,c.port),c.connectTimeoutMs); s.setSoTimeout(c.socketTimeoutMs); RedisConnection r=new RedisConnection(s); try { if(!c.password.isBlank()) r.command(c.username.isBlank()?new String[]{"AUTH",c.password}:new String[]{"AUTH",c.username,c.password}); if(c.database!=0)r.command("SELECT",Integer.toString(c.database)); if(!"PONG".equals(r.command("PING")))throw new IOException("Redis PING failed"); return r; } catch(Exception e){r.close();if(e instanceof IOException io)throw io;throw new IOException(e);} }
        Object command(String... args)throws IOException{write(args);output.flush();return read();}
        void exec(List<String[]> commands)throws IOException{for(String[] c:commands)write(c);output.flush();for(int i=0;i<commands.size();i++){Object reply=read();if(i==commands.size()-1&&(!(reply instanceof List<?> list)||list.size()!=commands.size()-2))throw new IOException("EXEC reply count mismatch");}}
        private void write(String[] args)throws IOException{output.write(("*"+args.length+"\r\n").getBytes(StandardCharsets.US_ASCII));for(String arg:args){byte[] b=arg.getBytes(StandardCharsets.UTF_8);output.write(("$"+b.length+"\r\n").getBytes(StandardCharsets.US_ASCII));output.write(b);output.write('\r');output.write('\n');}}
        private Object read()throws IOException{int p=input.read();if(p<0)throw new EOFException();return switch(p){case '+'->line();case '-'->throw new IOException("Redis: "+line());case ':'->Long.parseLong(line());case '$'->bulk();case '*'->array();default->throw new IOException("RESP prefix");};}
        private String line()throws IOException{var b=new java.io.ByteArrayOutputStream();int prev=-1;for(;;){int c=input.read();if(c<0)throw new EOFException();if(prev=='\r'&&c=='\n')return b.toString(StandardCharsets.UTF_8);if(prev>=0)b.write(prev);prev=c;}}
        private Object bulk()throws IOException{int n=Integer.parseInt(line());if(n<0)return null;byte[] b=input.readNBytes(n);if(b.length!=n||input.read()!='\r'||input.read()!='\n')throw new EOFException();return new String(b,StandardCharsets.UTF_8);}
        private Object array()throws IOException{int n=Integer.parseInt(line());if(n<0)return null;List<Object> a=new ArrayList<>(n);for(int i=0;i<n;i++)a.add(read());return a;}
        @Override public void close()throws IOException{socket.close();}
    }
}
