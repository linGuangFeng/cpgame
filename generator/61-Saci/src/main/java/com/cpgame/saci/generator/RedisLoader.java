package com.cpgame.saci.generator;

import com.cpgame.saci.generator.model.ResultAnalysis;
import com.cpgame.saci.generator.model.RoundMode;
import com.cpgame.saci.generator.model.RoundResult;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RedisLoader {
    private static final BigDecimal BET_SIZE = new BigDecimal("0.02");
    private static final int BET_LEVEL = 1;
    private static final BigDecimal START = new BigDecimal("10000.00");

    public LoadSummary load(GeneratorConfig config) throws Exception {
        GameRuleCore core = new GameRuleCore();
        RoundVerifier verifier = new RoundVerifier();
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec(new RoundFactory(), verifier);
        SecureRandom random = new SecureRandom();
        List<Member> pending = new ArrayList<>(config.batchSize);
        Counters counters = new Counters();
        try (RedisConnection redis = RedisConnection.connect(config)) {
            int lossLeft = config.outputLimits.lossTarget(config.lossCount);
            int winLeft = config.winCount;
            int specialLeft = config.specialCount;
            while (lossLeft > 0 || winLeft > 0 || specialLeft > 0) {
                if (winLeft > 0 && accept(core.generateOrdinaryWin(BET_LEVEL, BET_SIZE, START, random), RoundMode.ORDINARY_WIN,
                        false, config, verifier, codec, pending, redis, counters)) winLeft--;
                if (specialLeft > 0 && accept(core.generateSpecial(BET_LEVEL, BET_SIZE, START, random), null,
                        true, config, verifier, codec, pending, redis, counters)) specialLeft--;
                if (lossLeft > 0 && accept(core.generateIndependentLoss(BET_LEVEL, BET_SIZE, START, random), RoundMode.ORDINARY_LOSS,
                        false, config, verifier, codec, pending, redis, counters)) lossLeft--;
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

    private static void clearExistingGameKeys(RedisConnection redis, long gameId) throws Exception {
        List<String> keys = new ArrayList<>();
        keys.add(normalIndex(gameId));
        keys.add(specialIndex(gameId));
        for (String pattern : List.of(
                String.format(Locale.ROOT, "BetLog:0%08d:*", gameId),
                String.format(Locale.ROOT, "MaryLog:%09d:*", gameId))) {
            Object found = redis.command("KEYS", pattern);
            if (found instanceof List<?> list) for (Object key : list) keys.add(key.toString());
        }
        if (!keys.isEmpty()) {
            redis.command(java.util.stream.Stream.concat(
                    java.util.stream.Stream.of("DEL"), keys.stream()).toArray(String[]::new));
        }
    }

    private static boolean accept(RoundResult round, RoundMode expected, boolean special, GeneratorConfig config,
                               RoundVerifier verifier, MinimalRoundFactCodec codec, List<Member> pending,
                               RedisConnection redis, Counters counters) throws Exception {
        LoaderLimits.checkAttempts(++counters.candidates, (long) config.lossCount + config.winCount + config.specialCount);
        ResultAnalysis analysis = verifier.verify(round);
        if (expected != null && analysis.mode() != expected) return false;
        if (special && analysis.mode() != RoundMode.FREE_SPINS && analysis.mode() != RoundMode.WILD_VORTEX) {
            return false;
        }
        BigDecimal multiplier = round.totalWin().divide(analysis.bet(), 8, java.math.RoundingMode.HALF_UP);
        BigDecimal maximum = special ? config.specialMaxWinMultiplier : config.normalMaxWinMultiplier;
        if (multiplier.compareTo(maximum) > 0) return false;
        if (round.steps().size() > config.maxConsecutiveWins) {
            return false;
        }
        int ratio = analysis.multiplierHundredths();
        if (!config.outputLimits.acceptsHundredths(special, ratio)) return false;
        String payload = codec.encodeRedisMemberString(round);
        RoundResult rebuilt = codec.decodeRedisMember(payload, round.bs(), round.bl(), round.startingBalance());
        verifier.verifyRecovery(round, rebuilt);
        pending.add(new Member(special, ratio, payload));
        counters.accept(analysis.mode(), ratio, round.steps().size());
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
        long candidates;
        final Map<Integer, Integer> normalDistribution = new LinkedHashMap<>();
        final Map<Integer, Integer> specialDistribution = new LinkedHashMap<>();
        void accept(RoundMode mode, int multiplier, int deliveries) {
            if (mode == RoundMode.ORDINARY_LOSS) loss++;
            else if (mode == RoundMode.ORDINARY_WIN) win++;
            else special++;
            (mode == RoundMode.FREE_SPINS || mode == RoundMode.WILD_VORTEX ? specialDistribution : normalDistribution)
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
