package com.hd.cpgame.riocarnival.loader;

import com.hd.cpgame.riocarnival.core.GameRuleCore;
import com.hd.cpgame.riocarnival.core.GeneratedRound;
import com.hd.cpgame.riocarnival.core.RoundFactsCodec;
import com.hd.cpgame.riocarnival.core.RoundResult;
import com.hd.cpgame.riocarnival.core.RoundVerifier;
import com.hd.cpgame.riocarnival.core.SecureRoundRandom;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** SecureRandom 自然生成完整 Round，独立复核后按实际倍率直接写入平台 Redis。 */
public final class RedisLoader {
    private static final BigDecimal MINIMUM_BET_SIZE = new BigDecimal("0.02");
    private static final int MINIMUM_BET_LEVEL = 1;

    public LoadSummary load(GeneratorConfig config) throws Exception {
        GameRuleCore core = new GameRuleCore(new SecureRoundRandom(), config.normalWeights, config.freeWeights);
        RoundFactsCodec codec = new RoundFactsCodec();
        List<Member> pending = new ArrayList<Member>(config.batchSize);
        Counters counters = new Counters();
        try (RedisConnection redis = RedisConnection.connect(config)) {
            int lossLeft = config.outputLimits.lossTarget(config.lossCount);
            int winLeft = config.normalCount;
            int specialLeft = config.specialCount;
            long target = (long) config.normalCount + config.specialCount + config.lossCount;
            while (lossLeft > 0 || winLeft > 0 || specialLeft > 0) {
                LoaderLimits.checkAttempts(++counters.candidates, target);
                GeneratedRound round;
                RoundResult peek;
                try {
                    round = core.generate(MINIMUM_BET_SIZE, MINIMUM_BET_LEVEL);
                    peek = RoundVerifier.verify(round);
                } catch (RuntimeException rejected) {
                    counters.limitSkipped++;
                    continue;
                }
                boolean free = "FREE_SPINS".equals(peek.mode);
                int ratio = peek.redisRatio(round);
                if (free && specialLeft > 0) {
                    if (commit(round, true, false, config, codec, pending, redis, counters)) specialLeft--;
                    continue;
                }
                if (!free && ratio > 0 && winLeft > 0) {
                    if (commit(round, false, false, config, codec, pending, redis, counters)) winLeft--;
                    continue;
                }
                if (!free && ratio == 0 && lossLeft > 0) {
                    if (commit(round, false, true, config, codec, pending, redis, counters)) lossLeft--;
                    continue;
                }
                if (lossLeft > 0) {
                    try {
                        if (commit(core.generateIndependentLoss(MINIMUM_BET_SIZE, MINIMUM_BET_LEVEL),
                                false, true, config, codec, pending, redis, counters)) lossLeft--;
                    } catch (RuntimeException rejected) {
                        counters.limitSkipped++;
                    }
                }
            }
            flush(redis, pending, config, counters);
        } catch (IOException redisError) {
            if (counters.batches > 0 && redisProgressStop(redisError)) {
                System.out.println("[warn] Redis stopped after " + counters.batches
                        + " batches: " + redisError.getMessage());
                pending.clear();
            } else {
                throw redisError;
            }
        }
        System.out.println("lossMembers="+counters.loss);
        return counters.summary(config.redisGameId);
    }

    private static boolean commit(GeneratedRound round, boolean wantSpecial, boolean wantLoss,
                                  GeneratorConfig config, RoundFactsCodec codec, List<Member> pending,
                                  RedisConnection redis, Counters counters) throws Exception {
        RoundResult result = RoundVerifier.verify(round);
        boolean special = "FREE_SPINS".equals(result.mode);
        int ratio = result.redisRatio(round);
        if (wantSpecial != special) return false;
        if (wantLoss && ratio != 0) return false;
        if (!wantSpecial && !wantLoss && ratio <= 0) return false;
        if (!config.outputLimits.accepts(special, ratio)) return false;
        String member;
        GeneratedRound decoded;
        try {
            member = codec.encode(round);
            decoded = codec.decode(member);
        } catch (Exception invalidMember) {
            counters.limitSkipped++;
            return false;
        }
        RoundResult decodedResult;
        try {
            decodedResult = RoundVerifier.verify(decoded);
        } catch (RuntimeException invalidRound) {
            counters.limitSkipped++;
            return false;
        }
        if (!result.mode.equals(decodedResult.mode) || ratio != decodedResult.redisRatio(decoded)) {
            counters.limitSkipped++;
            return false;
        }
        pending.add(new Member(special, ratio, member));
        if (special) counters.special++;
        else if (ratio == 0) counters.loss++;
        else counters.normal++;
        counters.accept(special, ratio, result.freeStepCount);
        if (pending.size() >= config.batchSize) flush(redis, pending, config, counters);
        return true;
    }

    private static void flush(RedisConnection redis, List<Member> pending,
                              GeneratorConfig config, Counters counters) throws Exception {
        if (pending.isEmpty()) return;
        List<String[]> commands = new ArrayList<String[]>(pending.size() * 3);
        for (Member member : pending) {
            String ratio = Integer.toString(member.ratio);
            String index = member.special ? maryIndex(config.redisGameId) : normalIndex(config.redisGameId);
            String list = member.special ? maryList(config.redisGameId, member.ratio)
                                         : normalList(config.redisGameId, member.ratio);
            commands.add(new String[]{"ZADD", index, ratio, ratio});
            commands.add(new String[]{"RPUSH", list, member.payload});
            commands.add(new String[]{"LTRIM", list, "-" + (member.special ? config.outputLimits.specialCap : config.maxMembersPerMultiplier), "-1"});
        }
        redis.transaction(commands);
        counters.batches++;
        pending.clear();
    }

    static String normalIndex(long gameId) { return String.format(Locale.ROOT, "PerKeyList_%09d", gameId); }
    static String maryIndex(long gameId) { return String.format(Locale.ROOT, "MaryKeyList_%09d", gameId); }
    static String normalList(long gameId, int ratio) {
        return String.format(Locale.ROOT, "BetLog:0%08d:%06d", gameId, ratio);
    }
    static String maryList(long gameId, int ratio) {
        return String.format(Locale.ROOT, "MaryLog:%09d:%06d", gameId, ratio);
    }

    static boolean redisProgressStop(Throwable error) {
        String text = error == null ? "" : String.valueOf(error.getMessage());
        if (error != null && error.getCause() != null) text += " " + error.getCause().getMessage();
        return text.contains("OOM") || text.contains("maxmemory") || text.contains("timed out")
                || text.contains("Timed out") || text.contains("MISCONF") || text.contains("Connection reset")
                || text.contains("已关闭连接") || text.contains("closed") || text.contains("中止了一个已建立")
                || text.contains("EXECABORT") || text.contains("Broken pipe");
    }

    private static final class Member {
        final boolean special;
        final int ratio;
        final String payload;
        Member(boolean special, int ratio, String payload) {
            this.special = special;
            this.ratio = ratio;
            this.payload = payload;
        }
    }

    private static final class Counters {
        int loss;
        int normal;
        int special;
        int batches;
        int maxFreeSteps;
        long candidates;
        long zeroSkipped;
        long limitSkipped;
        final Map<Integer, Integer> normalDistribution = new LinkedHashMap<Integer, Integer>();
        final Map<Integer, Integer> specialDistribution = new LinkedHashMap<Integer, Integer>();

        void accept(boolean specialMode, int ratio, int freeSteps) {
            Map<Integer, Integer> distribution = specialMode ? specialDistribution : normalDistribution;
            Integer old = distribution.get(ratio);
            distribution.put(ratio, old == null ? 1 : old + 1);
            maxFreeSteps = Math.max(maxFreeSteps, freeSteps);
        }

        LoadSummary summary(long gameId) {
            return new LoadSummary(gameId, loss, normal, special, batches, candidates, zeroSkipped,
                limitSkipped, maxFreeSteps, normalDistribution, specialDistribution);
        }
    }

    public static final class LoadSummary {
        public final long redisGameId;
        public final int lossMembers;
        public final int normalMembers;
        public final int specialMembers;
        public final int batches;
        public final long candidates;
        public final long zeroSkipped;
        public final long limitSkipped;
        public final int maxFreeSteps;
        public final Map<Integer, Integer> normalDistribution;
        public final Map<Integer, Integer> specialDistribution;

        LoadSummary(long redisGameId, int lossMembers, int normalMembers, int specialMembers, int batches,
                    long candidates, long zeroSkipped, long limitSkipped, int maxFreeSteps,
                    Map<Integer, Integer> normalDistribution, Map<Integer, Integer> specialDistribution) {
            this.redisGameId = redisGameId;
            this.lossMembers = lossMembers;
            this.normalMembers = normalMembers;
            this.specialMembers = specialMembers;
            this.batches = batches;
            this.candidates = candidates;
            this.zeroSkipped = zeroSkipped;
            this.limitSkipped = limitSkipped;
            this.maxFreeSteps = maxFreeSteps;
            this.normalDistribution = new LinkedHashMap<Integer, Integer>(normalDistribution);
            this.specialDistribution = new LinkedHashMap<Integer, Integer>(specialDistribution);
        }
    }
}
