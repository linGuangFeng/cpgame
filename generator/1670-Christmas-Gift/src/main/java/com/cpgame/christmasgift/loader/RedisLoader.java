package com.cpgame.christmasgift.loader;

import com.cpgame.christmasgift.core.GameRuleCore;
import com.cpgame.christmasgift.core.GenerationModel;
import com.cpgame.christmasgift.core.MinimalRoundFactCodec;
import com.cpgame.christmasgift.core.RedisClient;
import com.cpgame.christmasgift.core.ResultUtil;
import com.cpgame.christmasgift.core.RoundFactory;
import com.cpgame.christmasgift.core.RoundVerifier;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Offline candidate generator and atomic Redis loader for game 1670. */
public final class RedisLoader {
    private RedisLoader() {}

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        Properties config = load(arguments.config());
        Settings settings = Settings.from(config);
        GenerationModel model = new GenerationModel(new SecureRandom(), settings.symbolWeights());
        RoundFactory factory = new RoundFactory(model);
        RoundVerifier verifier = new RoundVerifier();
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec();
        List<Pending> batch = new ArrayList<>();
        int ordinaryAccepted = 0;
        int featureAccepted = 0;
        long attempts = 0;

        try (RedisClient redis = arguments.dryRun() ? null : settings.open()) {
            while (ordinaryAccepted < settings.normalCount() || featureAccepted < settings.specialCount()) {
                boolean special = featureAccepted < settings.specialCount()
                    && (ordinaryAccepted >= settings.normalCount() || featureAccepted * settings.normalCount() <= ordinaryAccepted * settings.specialCount());
                GameRuleCore.CompleteRound candidate = special ? factory.christmasGiftFeature() : ordinaryAccepted == 0 && settings.outputLimits().accepts(false,0) ? factory.ordinaryLoss() : factory.ordinary();
                GameRuleCore.Evaluation evaluation = verifier.verify(candidate);
                int multiplier = ResultUtil.multiplier(candidate);
                int streak=0, longest=0;
                for(var step:evaluation.steps()){streak=step.wins().isEmpty()?0:streak+1;longest=Math.max(longest,streak);}
                if(longest>settings.maxConsecutiveWins()) {LoaderLimits.checkAttempts(++attempts,(long)settings.normalCount()+settings.specialCount());continue;}
                String index = special ? settings.specialIndexKey() : settings.normalIndexKey();
                String list = special ? settings.specialListKey(multiplier) : settings.normalListKey(multiplier);
                LoaderLimits.checkAttempts(++attempts, (long)settings.normalCount()+settings.specialCount());
                int maximum = special ? settings.specialMaxWinMultiplier() : settings.normalMaxWinMultiplier();
                if (!settings.outputLimits().accepts(special,multiplier) || multiplier > maximum) continue;
                batch.add(new Pending(index, list, Integer.toString(multiplier), codec.encode(candidate)));
                if (special) featureAccepted++; else ordinaryAccepted++;
            }
            replace(redis, batch, settings);
        }
        System.out.printf("生成完成 ordinary=%d feature=%d attempts=%d redisLoaded=%d dryRun=%s rulesHash=%s%n",
            ordinaryAccepted, featureAccepted, attempts, arguments.dryRun() ? 0 : batch.size(), arguments.dryRun(), GameRuleCore.RULES_HASH);
    }

    private static int consecutiveWins(List<Pending> batch) {
        int count = 0;
        for (int index = batch.size() - 1; index >= 0; index--) {
            if (batch.get(index).multiplier().equals("0")) break;
            count++;
        }
        return count;
    }

    private static void replace(RedisClient redis, List<Pending> batch, Settings settings) throws IOException {
        if (redis == null || batch.isEmpty()) return;
        Map<String, Bucket> buckets = new LinkedHashMap<>();
        for (Pending pending : batch) {
            Bucket bucket = buckets.computeIfAbsent(pending.listKey(), ignored ->
                new Bucket(pending.indexKey(), pending.multiplier(), new ArrayList<>()));
            bucket.members().add(pending.member());
        }
        Set<String> listsToDelete = new LinkedHashSet<>(buckets.keySet());
        for (String multiplier : redis.zrange(settings.normalIndexKey())) {
            listsToDelete.add(settings.normalListKey(Integer.parseInt(multiplier)));
        }
        for (String multiplier : redis.zrange(settings.specialIndexKey())) {
            listsToDelete.add(settings.specialListKey(Integer.parseInt(multiplier)));
        }

        redis.command("MULTI");
        for (String list : listsToDelete) redis.command("DEL", list);
        redis.command("DEL", settings.normalIndexKey());
        redis.command("DEL", settings.specialIndexKey());
        if (!(redis.command("EXEC") instanceof List<?>)) throw new IOException("Redis removal did not commit");
        int pending=0;
        for (Map.Entry<String, Bucket> entry : buckets.entrySet()) {
            Bucket bucket=entry.getValue();
            int cap=entry.getKey().startsWith("MaryLog:")?settings.outputLimits().specialCap:settings.maxMembersPerMultiplier();
            for(String member:bucket.members()) {
                if(pending==0)redis.command("MULTI");
                redis.command("ZADD",bucket.indexKey(),bucket.multiplier(),bucket.multiplier());
                redis.command("RPUSH",entry.getKey(),member);
                redis.command("LTRIM",entry.getKey(),Integer.toString(-cap),"-1");
                if(++pending>=settings.batchSize()) {if(!(redis.command("EXEC") instanceof List<?>))throw new IOException("Redis batch failed");pending=0;}
            }
        }
        if(pending>0 && !(redis.command("EXEC") instanceof List<?>))throw new IOException("Redis batch failed");
    }

    private static Properties load(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) { properties.load(input); LoaderLimits.checkKeys(properties); }
        return properties;
    }

    private record Pending(String indexKey, String listKey, String multiplier, String member) {}
    private record Bucket(String indexKey, String multiplier, List<String> members) {}
    private record Arguments(Path config, boolean dryRun) {
        static Arguments parse(String[] args) {
            Path config = Path.of("generator.properties");
            boolean dryRun = false;
            for (int index = 0; index < args.length; index++) {
                if ("--config".equals(args[index]) && index + 1 < args.length) config = Path.of(args[++index]);
                else if(args[index].startsWith("--config="))config=Path.of(args[index].substring(9));
                else if(!args[index].startsWith("--"))config=Path.of(args[index]);
                else if ("--dry-run".equals(args[index])) dryRun = true;
                else if (!"--no-pause".equals(args[index])) throw new IllegalArgumentException("unknown argument " + args[index]);
            }
            return new Arguments(config, dryRun);
        }
    }

    private record Settings(String host, int port, String username, String password, int database, boolean ssl,
                            int connectTimeoutMs, int socketTimeoutMs, int gameId, int normalCount, int specialCount,
                            int batchSize, int maxMembersPerMultiplier, int maxConsecutiveWins,
                            int normalMaxWinMultiplier, int specialMaxWinMultiplier, int[] symbolWeights, LoaderLimits outputLimits) {
        Settings(String host, int port, String username, String password, int database, boolean ssl,
                            int connectTimeoutMs, int socketTimeoutMs, int gameId, int normalCount, int specialCount,
                            int batchSize, int maxMembersPerMultiplier, int maxConsecutiveWins,
                            int normalMaxWinMultiplier, int specialMaxWinMultiplier, int[] symbolWeights) { this(host, port, username, password, database, ssl, connectTimeoutMs, socketTimeoutMs, gameId, normalCount, specialCount, batchSize, maxMembersPerMultiplier, maxConsecutiveWins, normalMaxWinMultiplier, specialMaxWinMultiplier, symbolWeights, new LoaderLimits(new java.util.Properties())); }

        static Settings from(Properties p) {
            int[] weights = new int[7];
            for (int symbol = 1; symbol <= 7; symbol++) {
                weights[symbol - 1] = positive(p, "generation.symbol." + symbol + ".weight");
            }
            Settings value = new Settings(required(p,"redis.host"), integer(p,"redis.port"), p.getProperty("redis.username",""),
                p.getProperty("redis.password",""), integer(p,"redis.database"), bool(p,"redis.ssl"),
                integer(p,"redis.connect-timeout-ms"), integer(p,"redis.socket-timeout-ms"), integer(p,"redis.game-id"),
                nonnegative(p,"generation.normal-count"), nonnegative(p,"generation.special-count"), positive(p,"generation.batch-size"),
                positive(p,"generation.max-members-per-multiplier"), positive(p,"generation.max-consecutive-wins"),
                positive(p,"generation.normal-max-win-multiplier"), positive(p,"generation.special-max-win-multiplier"), weights, new LoaderLimits(p));
            if (value.gameId <= 0 || value.host.isBlank() || value.port < 1 || value.port > 65535 || value.database < 0) {
                throw new IllegalArgumentException("Redis contract must remain 192.168.10.3:6379 db15 game 1670");
            }
            return value;
        }
        RedisClient open() throws IOException {
            return new RedisClient(host, port, username, password, database, ssl, connectTimeoutMs, socketTimeoutMs);
        }
        String normalIndexKey() { return String.format("PerKeyList_%09d", gameId); }
        String specialIndexKey() { return String.format("MaryKeyList_%09d", gameId); }
        String normalListKey(int multiplier) { return String.format("BetLog:0%08d:%06d", gameId, multiplier); }
        String specialListKey(int multiplier) { return String.format("MaryLog:%09d:%06d", gameId, multiplier); }
        private static String required(Properties p, String key) {
            String value = p.getProperty(key);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + key);
            return value.trim();
        }
        private static int integer(Properties p, String key) { return Integer.parseInt(required(p,key)); }
        private static int nonnegative(Properties p,String key){int n=integer(p,key);if(n<0)throw new IllegalArgumentException(key+" must be nonnegative");return n;}
        private static int positive(Properties p, String key) {
            int value = integer(p,key); if (value <= 0) throw new IllegalArgumentException(key + " must be positive"); return value;
        }
        private static boolean bool(Properties p, String key) { return Boolean.parseBoolean(required(p,key)); }
    }
}
