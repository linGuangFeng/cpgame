package com.cpgame.crazybirds.generator;

import com.cpgame.crazybirds.generator.model.ResultAnalysis;
import com.cpgame.crazybirds.generator.model.RoundMode;
import com.cpgame.crazybirds.generator.model.RoundResult;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/** 按尝试批次生成完整局并提交；失败候选计数且不补足。 */
public final class RedisLoader {
    private static final BigDecimal BET_SIZE = BigDecimal.ONE;
    private static final int BET_LEVEL = 1;
    private static final BigDecimal START = new BigDecimal("10000.00");

    public LoadSummary load(GeneratorConfig config) throws Exception {
        try (RedisConnection redis = RedisConnection.connect(config)) {
            return execute(config, config.generationCount, new SecureRandom(), false,
                    (members, ignored) -> flush(redis, members, config));
        }
    }

    /** 使用与正式 Loader 完全相同的生成、校验、分类和编码链路，但不连接或写入 Redis。 */
    public LoadSummary audit(GeneratorConfig config, long attempts, RandomGenerator random) throws Exception {
        if (attempts <= 0 || attempts > config.generationCount) {
            throw new IllegalArgumentException("审计次数必须在 1..generation.count 内");
        }
        return execute(config, attempts, random, true, (members, ignored) -> { });
    }

    private static LoadSummary execute(GeneratorConfig config, long attemptLimit, RandomGenerator random,
                                       boolean trackUniqueFacts, BatchSink sink) throws Exception {
        RuleBasedRoundGenerator generator = new RuleBasedRoundGenerator();
        RoundVerifier verifier = new RoundVerifier();
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec(new RoundFactory(), verifier);
        Counters counters = new Counters(trackUniqueFacts);
        long attempted = 0;
        long batchIndex = 0;
        while (attempted < attemptLimit) {
            int attemptsThisBatch = (int) Math.min(config.batchSize, attemptLimit - attempted);
            GenerationWeights.BatchWeights weights = config.weights.forBatch(batchIndex);
            List<Member> members = new ArrayList<>(attemptsThisBatch);
            counters.startBatch(weights, attemptsThisBatch);
            for (int i = 0; i < attemptsThisBatch; i++) {
                counters.attempts++;
                attempted++;
                try {
                    RoundResult round = generator.generate(BET_LEVEL, BET_SIZE, START, weights, random);
                    ResultAnalysis analysis = verifier.verify(round);
                    int ratio = GameRules.cacheMultiplier(analysis.totalMultiplier());
                    if (!config.acceptsCacheMultiplier(analysis.mode(), ratio)) {
                        counters.reject("MULTIPLIER_RANGE");
                        continue;
                    }
                    String payload = codec.encodeRedisMemberString(round);
                    RoundResult rebuilt = codec.decodeRedisMember(payload, round.bs(), round.bl(), round.startingBalance());
                    verifier.verifyRecovery(round, rebuilt);
                    boolean mary = analysis.mode() == RoundMode.FREE_SPINS;
                    int type = mary ? GameRules.FREE_SPINS_MARY_POOL_TYPE : GameRules.NORMAL_POOL_TYPE;
                    members.add(new Member(mary, type, ratio, payload));
                    counters.accept(analysis.mode(), ratio, round.steps().size(), payload);
                } catch (RuleBasedRoundGenerator.CandidateRejectedException rejected) {
                    counters.reject(rejected.reason());
                }
            }
            sink.write(List.copyOf(members), config);
            counters.finishBatch(members.size());
            batchIndex++;
        }
        return counters.summary(config.redisGameId);
    }

    private static void flush(RedisConnection redis, List<Member> members,
                              GeneratorConfig config) throws Exception {
        if (members.isEmpty()) return;
        Map<Bucket, List<String>> grouped = new LinkedHashMap<>();
        for (Member member : members) {
            grouped.computeIfAbsent(new Bucket(member.mary, member.type, member.ratio), ignored -> new ArrayList<>())
                    .add(member.payload);
        }
        List<String[]> commands = new ArrayList<>(grouped.size() * 3);
        for (Map.Entry<Bucket, List<String>> entry : grouped.entrySet()) {
            Bucket bucket = entry.getKey();
            String index = bucket.mary
                    ? maryIndex(config.redisGameId, bucket.type)
                    : normalIndex(config.redisGameId, bucket.type);
            String list = bucket.mary
                    ? maryList(config.redisGameId, bucket.type, bucket.ratio)
                    : normalList(config.redisGameId, bucket.type, bucket.ratio);
            String ratio = Integer.toString(bucket.ratio);
            commands.add(new String[]{"ZADD", index, ratio, ratio});
            String[] push = new String[2 + entry.getValue().size()];
            push[0] = "RPUSH";
            push[1] = list;
            for (int i = 0; i < entry.getValue().size(); i++) push[i + 2] = entry.getValue().get(i);
            commands.add(push);
            int cap = config.retention.capacity(bucket.mary, bucket.type, bucket.ratio);
            commands.add(new String[]{"LTRIM", list, "-" + cap, "-1"});
        }
        redis.transaction(commands);
    }

    public static String poolPrefix(long id, int type) {
        if (id < 1 || id > 99_999_999L) throw new IllegalArgumentException("redis.game-id 必须为1..8位");
        if (type < 0) throw new IllegalArgumentException("玩法类型不能为负数");
        return String.format(Locale.ROOT, "%d%08d", type, id);
    }

    public static String normalIndex(long id, int type) {
        return "PerKeyList_" + poolPrefix(id, type);
    }

    public static String maryIndex(long id, int type) {
        return "MaryKeyList_" + poolPrefix(id, type);
    }

    public static String normalList(long id, int type, int ratio) {
        if (ratio < 0) throw new IllegalArgumentException("倍率索引不能为负数");
        return String.format(Locale.ROOT, "BetLog:%s:%06d", poolPrefix(id, type), ratio);
    }

    public static String maryList(long id, int type, int ratio) {
        if (ratio < 0) throw new IllegalArgumentException("倍率索引不能为负数");
        return String.format(Locale.ROOT, "MaryLog:%s:%06d", poolPrefix(id, type), ratio);
    }

    @FunctionalInterface
    private interface BatchSink {
        void write(List<Member> members, GeneratorConfig config) throws Exception;
    }

    private record Member(boolean mary, int type, int ratio, String payload) { }
    private record Bucket(boolean mary, int type, int ratio) { }

    private static final class Counters {
        long attempts;
        long accepted;
        long rejected;
        long batches;
        long maxDeliveries;
        String currentPhase;
        final boolean trackUniqueFacts;
        final Set<String> uniqueFacts;
        final Map<String, Long> modes = new LinkedHashMap<>();
        final Map<String, Long> rejectionReasons = new LinkedHashMap<>();
        final Map<Integer, Long> normalDistribution = new LinkedHashMap<>();
        final Map<Integer, Long> freeDistribution = new LinkedHashMap<>();
        final Map<String, Long> phaseAttempts = new LinkedHashMap<>();
        final Map<String, Long> phaseAccepted = new LinkedHashMap<>();

        Counters(boolean trackUniqueFacts) {
            this.trackUniqueFacts = trackUniqueFacts;
            uniqueFacts = trackUniqueFacts ? new LinkedHashSet<>() : Set.of();
        }

        void startBatch(GenerationWeights.BatchWeights weights, int attemptsInBatch) {
            currentPhase = phaseName(weights);
            phaseAttempts.merge(currentPhase, (long) attemptsInBatch, Long::sum);
        }

        void finishBatch(int acceptedInBatch) {
            batches++;
            phaseAccepted.merge(currentPhase, (long) acceptedInBatch, Long::sum);
        }

        private String phaseName(GenerationWeights.BatchWeights weights) {
            return weights.boostedSymbol() == null ? "NEUTRAL" : "BOOST_" + weights.boostedSymbol();
        }

        void accept(RoundMode mode, int multiplier, int deliveries, String payload) {
            accepted++;
            modes.merge(mode.name(), 1L, Long::sum);
            (mode == RoundMode.FREE_SPINS ? freeDistribution : normalDistribution)
                    .merge(multiplier, 1L, Long::sum);
            maxDeliveries = Math.max(maxDeliveries, deliveries);
            if (trackUniqueFacts) uniqueFacts.add(payload);
        }

        void reject(String reason) {
            rejected++;
            rejectionReasons.merge(reason, 1L, Long::sum);
        }

        LoadSummary summary(long gameId) {
            return new LoadSummary(gameId, attempts, accepted, rejected, batches, maxDeliveries,
                    trackUniqueFacts ? uniqueFacts.size() : -1,
                    Map.copyOf(modes), Map.copyOf(rejectionReasons),
                    Map.copyOf(normalDistribution), Map.copyOf(freeDistribution),
                    Map.copyOf(phaseAttempts), Map.copyOf(phaseAccepted));
        }
    }

    public record LoadSummary(long redisGameId, long attempts, long accepted, long rejected,
                              long batches, long maxDeliveries, long uniqueFacts,
                              Map<String, Long> modes, Map<String, Long> rejectionReasons,
                              Map<Integer, Long> normalDistribution,
                              Map<Integer, Long> freeDistribution,
                              Map<String, Long> phaseAttempts,
                              Map<String, Long> phaseAccepted) { }
}
