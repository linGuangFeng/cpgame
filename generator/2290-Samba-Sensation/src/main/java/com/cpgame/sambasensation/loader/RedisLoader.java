package com.cpgame.sambasensation.loader;

import com.cpgame.sambasensation.core.GameRuleCore;
import com.cpgame.sambasensation.core.MinimalRoundFactCodec;
import com.cpgame.sambasensation.core.ResultUtil;
import com.cpgame.sambasensation.generator.CompleteRoundFactory;
import com.cpgame.sambasensation.generator.GeneratorConfig;
import com.cpgame.sambasensation.redis.RedisConnection;
import com.cpgame.sambasensation.redis.RedisKeyContract;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 正式 SecureRandom Loader：自然判奖、整数倍率入池、批内原子 RPUSH+LTRIM。 */
public final class RedisLoader {
    private RedisLoader() { }

    public static void main(String[] args) {
        try {
            if (args.length > 1) throw new IllegalArgumentException("用法: java -jar samba-sensation-redis-loader.jar [generator.properties]");
            Path configFile = Path.of(args.length == 0 ? "generator.properties" : args[0]).toAbsolutePath().normalize();
            Summary summary = run(configFile);
            System.out.printf("预生成完成 sourceGameId=%d redisGameId=%d normal=%d special=%d loss=%d win=%d free=%d coin=%d batches=%d rulesHash=%s%n",
                    GameRuleCore.GAME_ID, summary.redisGameId, summary.normal, summary.special,
                    summary.byClass.getOrDefault(GameRuleCore.RoundClass.ORDINARY_LOSS, 0),
                    summary.byClass.getOrDefault(GameRuleCore.RoundClass.ORDINARY_WIN, 0),
                    summary.byClass.getOrDefault(GameRuleCore.RoundClass.FREE_SPINS_SPECIAL, 0),
                    summary.byClass.getOrDefault(GameRuleCore.RoundClass.COIN_COLLECTION_REWARD, 0),
                    summary.batches, GameRuleCore.RULES_HASH);
        } catch (Exception failure) {
            System.err.println("[失败] " + failure.getMessage());
            System.exit(1);
        }
    }

    public static Summary run(Path configFile) throws Exception {
        GeneratorConfig config = GeneratorConfig.load(configFile);
        CompleteRoundFactory factory = new CompleteRoundFactory(config);
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec();
        SecureRandom random = new SecureRandom();
        Counters counters = new Counters();
        List<Member> pending = new ArrayList<>(config.batchSize);
        long attempts = 0;
        try (RedisConnection redis = RedisConnection.connect(config)) {
            System.out.printf("Redis已连接 host=%s port=%d db=%d；规则错误不会用网络回退掩盖%n", config.redisHost, config.redisPort, config.redisDatabase);
            while (counters.normal < config.normalCount || counters.special < config.specialCount) {
                LoaderLimits.checkAttempts(attempts, (long)config.normalCount+config.specialCount);
                boolean needNormal = counters.normal < config.normalCount;
                boolean needSpecial = counters.special < config.specialCount;
                boolean specialRequest = needSpecial && (!needNormal || random.nextBoolean());
                CompleteRoundFactory.GeneratedRound generated = specialRequest ? factory.generateSpecial(random) : factory.generateNormal(random);
                ResultUtil.Evaluation result = ResultUtil.evaluate(generated.fact());
                boolean special = isSpecial(result.roundClass());
                if (special && !needSpecial) { attempts++; continue; }
                if (!special && !needNormal) { attempts++; continue; }
                int min = special ? config.specialMinMultiplier : config.normalMinMultiplier;
                int max = special ? config.specialMaxMultiplier : config.normalMaxMultiplier;
                if ((result.multiplier() < min || result.multiplier() > max)
                        || result.maxConsecutiveWinningSteps() > config.maxConsecutiveWins) { counters.rejectedByRoundCap++; attempts++; continue; }
                if (generated.fact().steps().size() - 1 > Math.min(config.maxFreeSpins, GameRuleCore.FREE_STEPS)) { counters.rejectedByRoundCap++; attempts++; continue; }
                Map<Integer, Integer> perMultiplier = special ? counters.specialPerMultiplier : counters.normalPerMultiplier;
                int memberCap = special ? config.specialMaxMembersPerMultiplier : config.maxMembersPerMultiplier;
                String member = codec.encode(generated.fact());
                ResultUtil.Evaluation decoded = codec.verify(member);
                if (decoded.multiplier() != result.multiplier() || decoded.roundClass() != result.roundClass()) throw new IllegalStateException("Codec round-trip independent verification mismatch");
                pending.add(new Member(special, result.multiplier(), member));
                perMultiplier.merge(result.multiplier(), 1, Integer::sum);
                counters.byClass.merge(result.roundClass(), 1, Integer::sum);
                counters.byEntry.merge(generated.fact().entryKind(), 1, Integer::sum);
                if (special) counters.special++;
                if (!special) counters.normal++;
                if (pending.size() >= config.batchSize) flush(redis, pending, config, counters);
                attempts++;
                if (attempts > 50_000_000L) throw new IllegalStateException("生成尝试超出内部安全界限；请检查容量和网络配置，不得修改规则出牌");
            }
            flush(redis, pending, config, counters);
        }
        // Coverage is tested separately; restricted ranges need not contain every outcome.
        return new Summary(config.redisGameId, counters.normal, counters.special, counters.batches,
                Map.copyOf(counters.byClass), counters.rejectedByRoundCap, counters.rejectedByBucketCap);
    }

    private static void requireDemoCoverage(Counters counters) {
        for (GameRuleCore.RoundClass kind : GameRuleCore.RoundClass.values()) {
            if (counters.byClass.getOrDefault(kind, 0) < 1) throw new IllegalStateException("本次自然生成未覆盖试玩必需结果 " + kind + "，未宣称完成且未改规则追逐");
        }
        if (counters.byEntry.getOrDefault(GameRuleCore.EntryKind.FEATURE_BUY_INITIAL, 0) < 1) throw new IllegalStateException("同一特殊/mali池中缺少购买入口member，未另建购买池");
    }

    private static void flush(RedisConnection redis, List<Member> pending, GeneratorConfig config, Counters counters) throws Exception {
        if (pending.isEmpty()) return;
        List<String[]> commands = new ArrayList<>(pending.size() * 3);
        for (Member member : pending) {
            int betType = new MinimalRoundFactCodec().decode(member.value).betType();
            String index = member.special ? RedisKeyContract.specialIndex(config.redisGameId) : RedisKeyContract.normalIndex(config.redisGameId, betType);
            String list = member.special ? RedisKeyContract.specialList(config.redisGameId, member.multiplier) : RedisKeyContract.normalList(config.redisGameId, member.multiplier, betType);
            commands.add(new String[]{"ZADD", index, Integer.toString(member.multiplier), Integer.toString(member.multiplier)});
            commands.add(new String[]{"RPUSH", list, member.value});
            int cap = member.special ? config.specialMaxMembersPerMultiplier : config.maxMembersPerMultiplier;
            commands.add(new String[]{"LTRIM", list, "-" + cap, "-1"});
        }
        redis.transaction(commands);
        pending.clear();
        counters.batches++;
    }

    private static boolean isSpecial(GameRuleCore.RoundClass kind) {
        return switch (kind) {
            case FREE_SPINS_SPECIAL, COIN_COLLECTION_REWARD -> true;
            case ORDINARY_LOSS, ORDINARY_WIN -> false;
        };
    }

    private record Member(boolean special, int multiplier, String value) { }
    private static final class Counters {
        int normal;
        int special;
        int batches;
        int rejectedByRoundCap;
        int rejectedByBucketCap;
        final Map<Integer, Integer> normalPerMultiplier = new HashMap<>();
        final Map<Integer, Integer> specialPerMultiplier = new HashMap<>();
        final EnumMap<GameRuleCore.RoundClass, Integer> byClass = new EnumMap<>(GameRuleCore.RoundClass.class);
        final EnumMap<GameRuleCore.EntryKind, Integer> byEntry = new EnumMap<>(GameRuleCore.EntryKind.class);
    }
    public record Summary(long redisGameId, int normal, int special, int batches,
                          Map<GameRuleCore.RoundClass, Integer> byClass,
                          int rejectedByRoundCap, int rejectedByBucketCap) { }
}
