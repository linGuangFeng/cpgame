package com.cpgame.curupira.loader;

import com.cpgame.curupira.codec.MinimalFactCodec;
import com.cpgame.curupira.config.EngineConfiguration;
import com.cpgame.curupira.core.GameRuleCore;
import com.cpgame.curupira.core.ResultUtil;
import com.cpgame.curupira.core.RulesContract;
import com.cpgame.curupira.model.CompleteRoundFact;
import com.cpgame.curupira.model.CompleteRoundFact.Kind;
import com.cpgame.curupira.random.SecureRandomSource;
import com.cpgame.curupira.redis.RedisContractGate;
import com.cpgame.curupira.redis.RedisListClient;
import com.cpgame.curupira.verify.RoundVerifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 使用当前游戏 GameRuleCore 生成普通与特殊完整局，并直写平台 Redis。 */
public final class RedisLoader {
    private RedisLoader() { }

    public static void main(String[] args) throws Exception {
        LoadSummary summary = run(config(args));
        System.out.printf("生成完成 sourceGameId=%d redisGameId=%d written=%d loss=%d win=%d ew=%d free=%d hold=%d buckets=%d transactions=%d rulesHash=%s%n",
                RulesContract.GAME_ID, summary.redisGameId(), summary.written(),
                summary.counts().getOrDefault(Kind.LOSS, 0), summary.counts().getOrDefault(Kind.WIN, 0),
                summary.counts().getOrDefault(Kind.EXPANDING_WILD, 0),
                summary.counts().getOrDefault(Kind.FREE_EW, 0), summary.counts().getOrDefault(Kind.HOLD, 0),
                summary.multiplierBuckets(), summary.transactions(), summary.rulesHash());
    }

    public static LoadSummary run(Path configPath) throws Exception {
        return run(EngineConfiguration.load(configPath));
    }

    public static LoadSummary run(EngineConfiguration config) throws Exception {
        com.cpgame.curupira.core.GenerationPolicy configuredPolicy = config.generationPolicy();
        com.cpgame.curupira.core.GenerationPolicy candidatePolicy = new com.cpgame.curupira.core.GenerationPolicy(
                configuredPolicy.symbolWeights(), configuredPolicy.constructionAttempts(), configuredPolicy.fallbackSamples(),
                Integer.MAX_VALUE, configuredPolicy.maxSpecialSteps(), configuredPolicy.maxConsecutiveWins());
        GameRuleCore core = new GameRuleCore(new SecureRandomSource(), candidatePolicy);
        RoundVerifier verifier = new RoundVerifier(candidatePolicy);
        ResultUtil resultUtil = new ResultUtil();
        MinimalFactCodec codec = new MinimalFactCodec();
        RedisContractGate keys = new RedisContractGate();
        Set<Integer> buckets = new HashSet<>();
        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        List<RedisListClient.Entry> pending = new ArrayList<>(config.batchSize());
        long written = 0, transactions = 0;
        int ordinary = config.normalCount();
        int special = Math.max(config.specialCount(), 0);
        int loss = config.outputLimits().lossTarget(ordinary / 3);
        int win = ordinary - loss - ordinary / 3;
        int ew = ordinary - loss - win;
        int free = special == 0 ? 0 : Math.max(1, special / 2);
        int hold = special == 0 ? 0 : Math.max(0, special - free);
        try (RedisListClient redis = new RedisListClient(config)) {
            redis.clearGame(config.redisGameId());
            written += write(core, verifier, resultUtil, codec, keys, pending, buckets, counts,
                    Kind.LOSS, loss, config, redis);
            written += write(core, verifier, resultUtil, codec, keys, pending, buckets, counts, Kind.WIN, win, config, redis);
            written += write(core, verifier, resultUtil, codec, keys, pending, buckets, counts,
                    Kind.EXPANDING_WILD, ew, config, redis);
            written += write(core, verifier, resultUtil, codec, keys, pending, buckets, counts,
                    Kind.FREE_EW, free, config, redis);
            written += write(core, verifier, resultUtil, codec, keys, pending, buckets, counts,
                    Kind.HOLD, hold, config, redis);
            if (!pending.isEmpty()) {
                redis.appendBatch(pending, config.maxMembersPerMultiplier());
                pending.clear();
                transactions++;
            }
        }
        return new LoadSummary(config.redisGameId(), written, transactions, buckets.size(),
                Map.copyOf(counts), RulesContract.RULES_HASH);
    }

    private static int write(GameRuleCore core, RoundVerifier verifier, ResultUtil resultUtil,
                             MinimalFactCodec codec, RedisContractGate keys,
                             List<RedisListClient.Entry> pending, Set<Integer> buckets,
                             Map<Kind, Integer> counts, Kind kind, int target,
                             EngineConfiguration config, RedisListClient redis) throws Exception {
        int produced = 0;
        long attempts=0;
        for (int i = 0; i < target; i++) {
            com.cpgame.curupira.config.LoaderLimits.checkAttempts(++attempts, target);
            CompleteRoundFact fact = core.generateFact(kind);
            verifier.verifyFact(fact);
            int multiplier = resultUtil.redisMultiplier(fact);
            if (kind == Kind.TRIGGER) {
                throw new IllegalStateException("trigger boards are live and must not be written to Redis");
            }
            if (!config.outputLimits().accepts(!kind.ordinary(), multiplier) || multiplier > (kind.ordinary() ? config.normalMaxWinMultiplier() : config.specialMaxWinMultiplier())) {
                i--;
                continue;
            }
            String member = codec.encodeFact(fact);
            CompleteRoundFact decoded = codec.decode(member);
            verifier.verifyFact(decoded);
            if (decoded.kind() != fact.kind() || decoded.entry() != fact.entry()
                    || decoded.steps().size() != fact.steps().size()
                    || resultUtil.redisMultiplier(decoded) != multiplier) {
                throw new IllegalStateException("Codec 回读完整局事实不一致");
            }
            pending.add(new RedisListClient.Entry(
                    keys.indexesToWrite(fact.kind(), config.redisGameId()),
                    keys.listFor(fact.kind(), multiplier, config.redisGameId()),
                    multiplier, member));
            buckets.add(multiplier);
            counts.merge(kind, 1, Integer::sum);
            produced++;
            if (pending.size() >= config.batchSize()) {
                redis.appendBatch(pending, config.maxMembersPerMultiplier());
                pending.clear();
            }
        }
        return produced;
    }

    private static Path config(String[] args) {
        for (String arg : args) if (arg.toLowerCase().contains("seed")) {
            throw new IllegalArgumentException("正式 Loader 禁止 seed 参数");
        }
        if (args.length > 1) throw new IllegalArgumentException("用法：java -jar loader.jar [generator.properties]");
        return Path.of(args.length == 0 ? "generator.properties" : args[0]).toAbsolutePath().normalize();
    }

    public record LoadSummary(int redisGameId, long written, long transactions, int multiplierBuckets,
                              Map<Kind, Integer> counts, String rulesHash) { }
}
