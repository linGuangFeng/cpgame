package com.cpgame.crazypiggy.generator;

import com.cpgame.crazypiggy.generator.model.ResultAnalysis;
import com.cpgame.crazypiggy.generator.model.RoundMode;
import com.cpgame.crazypiggy.generator.model.RoundResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 仅在部署前生成完整局；Controller 运行时没有任何生成分支。 */
public final class RedisLoader {
    public LoadSummary load(GeneratorConfig config) throws Exception {
        RoundVerifier verifier = new RoundVerifier();
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec(new RoundFactory(), verifier);
        List<Member> pending = new ArrayList<>(config.batchSize);
        Counters counters = new Counters();
        try (RedisConnection redis = RedisConnection.connect(config)) {
            try {
                EnumerationLoader.GenerationSummary generated = EnumerationLoader.generate(config, (round, analysis) -> {
                    try {
                        accept(round, analysis, config, verifier, codec, pending, redis, counters);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                });
                counters.candidates = generated.attempts();
            } catch (RuntimeException wrapped) {
                Throwable cause = wrapped.getCause() == null ? wrapped : wrapped.getCause();
                if (cause instanceof java.io.IOException redisError) throw redisError;
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw wrapped;
            }
            flush(redis, pending, config, counters);
        } catch (java.io.IOException redisError) {
            if (counters.batches > 0 && redisProgressStop(redisError)) {
                System.out.println("[warn] Redis stopped after " + counters.batches
                        + " batches: " + redisError.getMessage());
                pending.clear();
            } else {
                throw redisError;
            }
        }
        return counters.summary(config.redisGameId);
    }

    private static void accept(RoundResult round, ResultAnalysis analysis, GeneratorConfig config,
                               RoundVerifier verifier, MinimalRoundFactCodec codec, List<Member> pending,
                               RedisConnection redis, Counters counters) throws Exception {
        BigDecimal multiplier = analysis.totalAward().divide(analysis.betAmount()).stripTrailingZeros();
        int ratio = multiplier.intValueExact();
        if (!config.outputLimits.accepts(false, ratio))
            throw new IllegalStateException("enumeration produced a ratio outside the configured range");
        int consecutiveWins = round.boosterWheel() ? 1 + round.wheelMultipliers().size() : (ratio == 0 ? 0 : 1);
        if (consecutiveWins > config.maxConsecutiveWins)
            throw new IllegalStateException("enumeration exceeded max-consecutive-wins");
        String payload = codec.encodeRedisMemberString(round);
        RoundResult rebuilt = codec.decodeRedisMember(payload);
        ResultAnalysis rebuiltAnalysis = verifier.verify(rebuilt);
        BigDecimal rebuiltMultiplier = rebuiltAnalysis.totalAward().divide(rebuiltAnalysis.betAmount()).stripTrailingZeros();
        if (rebuiltMultiplier.compareTo(multiplier) != 0)
            throw new IllegalStateException("Redis member 恢复后的倍率不一致");
        if (!payload.equals("#") && (!round.symbols().equals(rebuilt.symbols())
                || !round.wheelPositions().equals(rebuilt.wheelPositions())
                || !round.wheelMultipliers().equals(rebuilt.wheelMultipliers())))
            throw new IllegalStateException("Redis member 最小事实不一致");
        pending.add(new Member(ratio, payload));
        counters.accept(analysis.mode(), ratio, round.deliveries().size());
        if (pending.size() >= config.batchSize) flush(redis, pending, config, counters);
    }

    private static void flush(RedisConnection redis, List<Member> pending,
                              GeneratorConfig config, Counters counters) throws Exception {
        if (pending.isEmpty()) return;
        List<String[]> commands = new ArrayList<>(pending.size() * 3);
        for (Member member : pending) {
            String index = normalIndex(config.redisGameId);
            String list = normalList(config.redisGameId, member.ratio);
            String ratio = Integer.toString(member.ratio);
            commands.add(new String[]{"ZADD", index, ratio, ratio});
            commands.add(new String[]{"RPUSH", list, member.payload});
            commands.add(new String[]{"LTRIM", list, "-" + config.maxMembersPerMultiplier, "-1"});
        }
        redis.transaction(commands);
        counters.batches++;
        pending.clear();
    }

    public static String normalIndex(long id) { return String.format(Locale.ROOT, "PerKeyList_%09d", id); }
    public static String specialIndex(long id) { return String.format(Locale.ROOT, "MaryKeyList_%09d", id); }
    public static String normalList(long id, int ratio) {
        return String.format(Locale.ROOT, "BetLog:0%08d:%06d", id, ratio);
    }
    public static String specialList(long id, int ratio) {
        return String.format(Locale.ROOT, "MaryLog:%09d:%06d", id, ratio);
    }

    static boolean redisProgressStop(Throwable error) {
        String text = error == null ? "" : String.valueOf(error.getMessage());
        if (error != null && error.getCause() != null) text += " " + error.getCause().getMessage();
        return text.contains("OOM") || text.contains("maxmemory") || text.contains("timed out")
                || text.contains("Timed out") || text.contains("MISCONF") || text.contains("Connection reset")
                || text.contains("closed") || text.contains("EXECABORT") || text.contains("Broken pipe")
                || text.contains("已关闭连接") || text.contains("中止了一个已建立");
    }

    private record Member(int ratio, String payload) {}

    private static final class Counters {
        int loss, win, batches, maxDeliveries;
        long candidates;
        final Map<Integer, Integer> normalDistribution = new LinkedHashMap<>();
        void accept(RoundMode mode, int multiplier, int deliveries) {
            if (mode == RoundMode.ORDINARY_LOSS) loss++;
            else win++;
            normalDistribution.merge(multiplier, 1, Integer::sum);
            maxDeliveries = Math.max(maxDeliveries, deliveries);
        }
        LoadSummary summary(long gameId) {
            return new LoadSummary(gameId, loss, win, 0, batches, candidates, maxDeliveries,
                    Map.copyOf(normalDistribution), Map.of());
        }
    }

    public record LoadSummary(long redisGameId, int lossMembers, int winMembers, int specialMembers,
                              int batches, long candidates, int maxDeliveries,
                              Map<Integer, Integer> normalDistribution,
                              Map<Integer, Integer> specialDistribution) {}
}
