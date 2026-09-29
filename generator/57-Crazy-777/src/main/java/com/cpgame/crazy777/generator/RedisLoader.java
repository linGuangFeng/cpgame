package com.cpgame.crazy777.generator;

import com.cpgame.crazy777.generator.model.ResultAnalysis;
import com.cpgame.crazy777.generator.model.RoundCandidate;
import com.cpgame.crazy777.generator.model.RoundMode;
import com.cpgame.crazy777.generator.model.RoundResult;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 仅在部署前生成完整局；Controller 运行时没有任何生成分支。 */
public final class RedisLoader {
    private static final BigDecimal BET_SIZE = new BigDecimal("0.5");
    private static final int BET_LEVEL = 1;
    private static final BigDecimal START = new BigDecimal("10000.00");

    public enum Pool { ORDINARY, MARY }

    public LoadSummary loadOrdinary(GeneratorConfig config) throws Exception {
        return load(config, Pool.ORDINARY);
    }

    public LoadSummary loadMary(GeneratorConfig config) throws Exception {
        return load(config, Pool.MARY);
    }

    public LoadSummary load(GeneratorConfig config, Pool pool) throws Exception {
        if (pool == null) throw new IllegalArgumentException("必须指定 ordinary 或 mary");
        RoundVerifier verifier = new RoundVerifier();
        SecureRandom random = new SecureRandom();
        List<Member> pending = new ArrayList<>(config.batchSize);
        Counters counters = new Counters();
        try (RedisConnection redis = RedisConnection.connect(config)) {
            RoundFactory factory = new RoundFactory();
            MinimalRoundFactCodec codec = new MinimalRoundFactCodec(factory, verifier);
            clearExistingPoolKeys(redis, config.redisGameId, pool);
            if (pool == Pool.ORDINARY) {
                writeOrdinary(config, factory, verifier, codec, pending, redis, counters, random);
            } else {
                writeMary(config, verifier, codec, pending, redis, counters, random);
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

    private static void writeOrdinary(GeneratorConfig config, RoundFactory factory, RoundVerifier verifier,
                                      MinimalRoundFactCodec codec, List<Member> pending, RedisConnection redis,
                                      Counters counters, SecureRandom random) throws Exception {
        List<OrdinaryRewardEnumerator.Board> ordinary = OrdinaryRewardEnumerator.enumerate(
                config.outputLimits, config.outputLimits.ordinaryCap, random);
        System.out.printf("ORDINARY_START boards=%d ordinaryCap=%d%n",
                ordinary.size(), config.outputLimits.ordinaryCap);
        for (OrdinaryRewardEnumerator.Board board : ordinary) {
            RoundMode expected = board.ratio() == 0 ? RoundMode.ORDINARY_LOSS : RoundMode.ORDINARY_WIN;
            RoundResult round = factory.create(new RoundCandidate(List.of(board.cells())),
                    BET_LEVEL, BET_SIZE, START);
            if (!accept(round, expected, false, config, verifier, codec, pending, redis, counters, false)) {
                throw new IllegalStateException("enumerated ordinary rejected ratio=" + board.ratio());
            }
        }
    }

    private static void writeMary(GeneratorConfig config, RoundVerifier verifier, MinimalRoundFactCodec codec,
                                  List<Member> pending, RedisConnection redis, Counters counters,
                                  SecureRandom random) throws Exception {
        GameRuleCore core = new GameRuleCore(config.symbolWeights);
        System.out.printf("MARY_START specialCount=%d specialCap=%d%n",
                config.specialCount, config.outputLimits.specialCap);
        int specialLeft = config.specialCount;
        while (specialLeft > 0) {
            if (accept(core.generateFreeSpins(BET_LEVEL, BET_SIZE, START, random), RoundMode.FREE_SPINS,
                    true, config, verifier, codec, pending, redis, counters, true)) specialLeft--;
        }
    }

    private static void clearExistingPoolKeys(RedisConnection redis, long gameId, Pool pool) throws Exception {
        List<String> keys = new ArrayList<>();
        boolean special = pool == Pool.MARY;
        keys.add(special ? specialIndex(gameId) : normalIndex(gameId));
        String pattern = special
                ? String.format(Locale.ROOT, "MaryLog:%09d:*", gameId)
                : String.format(Locale.ROOT, "BetLog:0%08d:*", gameId);
        Object found = redis.command("KEYS", pattern);
        if (found instanceof List<?> list) for (Object key : list) keys.add(key.toString());
        if (!keys.isEmpty()) {
            redis.command(java.util.stream.Stream.concat(
                    java.util.stream.Stream.of("DEL"), keys.stream()).toArray(String[]::new));
        }
    }

    private static boolean accept(RoundResult round, RoundMode expected, boolean special, GeneratorConfig config,
                               RoundVerifier verifier, MinimalRoundFactCodec codec, List<Member> pending,
                               RedisConnection redis, Counters counters, boolean limitAttempts) throws Exception {
        counters.candidates++;
        if (limitAttempts) LoaderLimits.checkAttempts(++counters.maryAttempts, config.specialCount);
        ResultAnalysis analysis = verifier.verify(round);
        if (analysis.mode() != expected) return false;
        BigDecimal multiplier = analysis.totalMultiplier();
        int ratio = multiplier.intValueExact();
        if (!config.outputLimits.accepts(special, ratio)) return false;
        BigDecimal maximum = special ? config.specialMaxWinMultiplier : config.normalMaxWinMultiplier;
        if (multiplier.compareTo(maximum) > 0) return false;
        if (round.steps().size() > config.maxConsecutiveWins) {
            return false;
        }
        String payload = codec.encodeRedisMemberString(round);
        RoundResult rebuilt = codec.decodeRedisMember(payload, round.bs(), round.bl(), round.startingBalance());
        ResultAnalysis rebuiltAnalysis = verifier.verify(rebuilt);
        if (rebuiltAnalysis.mode() != expected || rebuiltAnalysis.totalMultiplier().compareTo(multiplier) != 0)
            throw new IllegalStateException("Redis member 恢复后的类别或倍率不一致");
        if (!payload.equals("#")) verifier.verifyRecovery(round, rebuilt);
        pending.add(new Member(special, ratio, payload));
        counters.accept(expected, ratio, round.steps().size());
        if (pending.size() >= config.batchSize) flush(redis, pending, config, counters);
        return true;
    }

    private static void flush(RedisConnection redis, List<Member> pending,
                              GeneratorConfig config, Counters counters) throws Exception {
        if (pending.isEmpty()) return;
        List<String[]> commands = new ArrayList<>(pending.size() * 3);
        for (Member member : pending) {
            String index = member.special ? specialIndex(config.redisGameId) : normalIndex(config.redisGameId);
            String list = member.special ? specialList(config.redisGameId, member.ratio)
                    : normalList(config.redisGameId, member.ratio);
            String ratio = Integer.toString(member.ratio);
            commands.add(new String[]{"ZADD", index, ratio, ratio});
            commands.add(new String[]{"RPUSH", list, member.payload});
            int cap = member.special ? config.outputLimits.specialCap : config.maxMembersPerMultiplier;
            commands.add(new String[]{"LTRIM", list, "-" + cap, "-1"});
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

    private record Member(boolean special, int ratio, String payload) {}

    private static final class Counters {
        int loss, win, special, batches, maxDeliveries;
        long candidates, maryAttempts;
        final Map<Integer, Integer> normalDistribution = new LinkedHashMap<>();
        final Map<Integer, Integer> specialDistribution = new LinkedHashMap<>();
        void accept(RoundMode mode, int multiplier, int deliveries) {
            if (mode == RoundMode.ORDINARY_LOSS) loss++;
            else if (mode == RoundMode.ORDINARY_WIN) win++;
            else special++;
            (mode == RoundMode.FREE_SPINS ? specialDistribution : normalDistribution)
                    .merge(multiplier, 1, Integer::sum);
            maxDeliveries = Math.max(maxDeliveries, deliveries);
        }
        LoadSummary summary(long gameId) {
            return new LoadSummary(gameId, loss, win, special, batches, candidates, maxDeliveries,
                    Map.copyOf(normalDistribution), Map.copyOf(specialDistribution));
        }
    }

    public record LoadSummary(long redisGameId, int lossMembers, int winMembers, int specialMembers,
                              int batches, long candidates, int maxDeliveries,
                              Map<Integer, Integer> normalDistribution,
                              Map<Integer, Integer> specialDistribution) {}
}
