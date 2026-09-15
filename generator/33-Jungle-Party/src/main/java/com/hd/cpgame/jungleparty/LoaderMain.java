package com.hd.cpgame.jungleparty;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Formal offline generator/loader. Runtime delivery only consumes these verified complete Rounds. */
public final class LoaderMain {
    private LoaderMain() {}

    public static void main(String[] args) throws Exception {
        boolean dryRun = has(args, "--dry-run");
        Path configPath = Path.of(option(args, "--config", args.length==1&&!args[0].startsWith("--")?args[0]:"generator.properties"));
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(configPath)) { properties.load(input); LoaderLimits.checkKeys(properties); }
        Config config = Config.read(properties);
        SecureRandom random = new SecureRandom();
        Map<String,Integer> counts = new LinkedHashMap<>();
        RedisRoundWriter redis = dryRun ? null : new RedisRoundWriter(config.redisHost, config.redisPort,
            config.connectTimeoutMs, config.socketTimeoutMs, config.ssl);
        try {
            if (redis != null) { redis.batchSize(config.batchSize); redis.auth(config.redisUsername, config.redisPassword); redis.select(config.redisDatabase); }
            int normal = 0, special = 0, consecutiveWins = 0;
            long attempts = 0;
            long attemptLimit = LoaderLimits.attemptLimit((long) config.normalCount + config.specialCount);
            while ((normal < config.normalCount || special < config.specialCount) && attempts++ < attemptLimit) {
                GameRuleCore.Round round = GameRuleCore.generate(random, GameRuleCore.Scenario.RANDOM, 1, new BigDecimal("0.02"));
                GameRuleCore.Round decoded = MemberCodec.decode(MemberCodec.encode(round));
                IndependentVerifier.Verification verification = IndependentVerifier.verify(decoded);
                if (!verification.pass()) throw new IllegalStateException("independent verification failed: " + verification.errors());
                int multiplier = ResultUtil.multiplier(decoded);
                boolean isSpecial = ResultUtil.special(decoded);
                if (isSpecial && special >= config.specialCount) continue;
                if (!isSpecial && normal >= config.normalCount) continue;
                int winningStreak=0, longestStreak=0;
                for(var delivery:decoded.deliveries()){winningStreak=delivery.award().signum()>0?winningStreak+1:0;longestStreak=Math.max(longestStreak,winningStreak);}
                if(longestStreak>config.maxConsecutiveWins)continue;
                int maxMultiplier = isSpecial ? config.specialMaxWinMultiplier : config.normalMaxWinMultiplier;
                if (!config.outputLimits().acceptsHundredths(isSpecial,multiplier)) continue;
                String index = isSpecial ? RedisKeyContract.specialIndex(config.gameId) : RedisKeyContract.normalIndex(config.gameId);
                String list = isSpecial ? RedisKeyContract.specialList(config.gameId, multiplier) : RedisKeyContract.normalList(config.gameId, multiplier);
                if (redis != null) redis.appendBounded(index, list, multiplier, MemberCodec.encode(decoded),
                    isSpecial ? config.outputLimits().specialCap : config.maxMembersPerMultiplier);
                if (isSpecial) special++; else normal++;
                consecutiveWins = !isSpecial && multiplier > 0 ? consecutiveWins + 1 : 0;
                counts.merge((isSpecial ? "SPECIAL" : multiplier == 0 ? "LOSS" : "WIN") + ":" + multiplier, 1, Integer::sum);
            }
            if (normal != config.normalCount || special != config.specialCount) throw new IllegalStateException("generation targets not reached");
            if(redis!=null)redis.flush();
            System.out.println("LOAD_COMPLETE gid33 formal loader PASS dryRun=" + dryRun + " normal=" + normal + " special=" + special + " buckets=" + counts);
        } finally { if (redis != null) redis.close(); }
    }

    private record Config(long gameId, String redisHost, int redisPort, String redisUsername,
                          String redisPassword, int redisDatabase, boolean ssl, int connectTimeoutMs,
                          int socketTimeoutMs, int normalCount, int specialCount, int batchSize,
                          int maxMembersPerMultiplier, int maxConsecutiveWins,
                          int normalMaxWinMultiplier, int specialMaxWinMultiplier,
                          Map<String,Integer> symbolWeights, LoaderLimits outputLimits) {
        Config(long gameId, String redisHost, int redisPort, String redisUsername,
                          String redisPassword, int redisDatabase, boolean ssl, int connectTimeoutMs,
                          int socketTimeoutMs, int normalCount, int specialCount, int batchSize,
                          int maxMembersPerMultiplier, int maxConsecutiveWins,
                          int normalMaxWinMultiplier, int specialMaxWinMultiplier,
                          Map<String,Integer> symbolWeights) { this(gameId, redisHost, redisPort, redisUsername, redisPassword, redisDatabase, ssl, connectTimeoutMs, socketTimeoutMs, normalCount, specialCount, batchSize, maxMembersPerMultiplier, maxConsecutiveWins, normalMaxWinMultiplier, specialMaxWinMultiplier, symbolWeights, new LoaderLimits(new java.util.Properties())); }

        static Config read(Properties p) {
for(String key:p.stringPropertyNames())if(key.startsWith("generation.symbol."))throw new IllegalArgumentException("该配置不控制当前联合模型，已从正式配置移除: "+key);
            long gameId = positiveLong(p, "redis.game-id"); if (gameId <= 0) throw new IllegalArgumentException("redis.game-id must be positive");
            Map<String,Integer> symbolWeights = new LinkedHashMap<>();
            for (String key : p.stringPropertyNames()) if (key.startsWith("generation.symbol.") && key.endsWith(".weight")) symbolWeights.put(key, positive(p, key));

            return new Config(gameId, required(p,"redis.host"), positive(p,"redis.port"), p.getProperty("redis.username","").trim(),
                p.getProperty("redis.password",""), nonnegative(p,"redis.database"), Boolean.parseBoolean(required(p,"redis.ssl")),
                positive(p,"redis.connect-timeout-ms"), positive(p,"redis.socket-timeout-ms"), nonnegative(p,"generation.normal-count"),
                nonnegative(p,"generation.special-count"), positive(p,"generation.batch-size"), positive(p,"generation.max-members-per-multiplier"),
                positive(p,"generation.max-consecutive-wins"), positive(p,"generation.normal-max-win-multiplier"),
                positive(p,"generation.special-max-win-multiplier"), Map.copyOf(symbolWeights), new LoaderLimits(p));
        }
        private static String required(Properties p,String key){String value=p.getProperty(key);if(value==null||value.isBlank())throw new IllegalArgumentException("missing "+key);return value.trim();}
        private static int positive(Properties p,String key){int value=Integer.parseInt(required(p,key));if(value<=0)throw new IllegalArgumentException(key+" must be positive");return value;}
        private static int nonnegative(Properties p,String key){int value=Integer.parseInt(required(p,key));if(value<0)throw new IllegalArgumentException(key+" must be nonnegative");return value;}
        private static long positiveLong(Properties p,String key){long value=Long.parseLong(required(p,key));if(value<=0)throw new IllegalArgumentException(key+" must be positive");return value;}
    }

    private static boolean has(String[] args,String name){for(String arg:args)if(arg.equals(name))return true;return false;}
    private static String option(String[] args,String name,String fallback){for(String arg:args)if(arg.startsWith(name+"="))return arg.substring(name.length()+1);for(int i=0;i+1<args.length;i++)if(args[i].equals(name))return args[i+1];return fallback;}
}
