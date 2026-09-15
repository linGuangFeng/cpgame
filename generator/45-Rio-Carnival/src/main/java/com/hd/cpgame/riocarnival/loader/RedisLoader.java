package com.hd.cpgame.riocarnival.loader;

import com.hd.cpgame.riocarnival.core.GameRuleCore;
import com.hd.cpgame.riocarnival.core.GeneratedRound;
import com.hd.cpgame.riocarnival.core.RoundFactsCodec;
import com.hd.cpgame.riocarnival.core.RoundResult;
import com.hd.cpgame.riocarnival.core.RoundVerifier;
import com.hd.cpgame.riocarnival.core.SecureRoundRandom;
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
        GameRuleCore core = new GameRuleCore(new SecureRoundRandom());
        RoundFactsCodec codec = new RoundFactsCodec();
        List<Member> pending = new ArrayList<Member>(config.batchSize);
        Counters counters = new Counters();
        try (RedisConnection redis = RedisConnection.connect(config)) {
            while (counters.normal < config.normalCount || counters.special < config.specialCount || counters.loss < config.outputLimits.lossTarget(config.lossCount)) {
                LoaderLimits.checkAttempts(++counters.candidates, (long) config.normalCount + config.specialCount + config.lossCount);
                GeneratedRound round = counters.loss < config.outputLimits.lossTarget(config.lossCount) ? core.generateIndependentLoss(MINIMUM_BET_SIZE, MINIMUM_BET_LEVEL) : core.generate(MINIMUM_BET_SIZE, MINIMUM_BET_LEVEL);
                RoundResult result = RoundVerifier.verify(round);
                boolean special = "FREE_SPINS".equals(result.mode);
                int ratio = result.redisRatio(round);
                if (!config.outputLimits.accepts(special, ratio)) continue;
                if (special && counters.special>=config.specialCount) continue;
                if (!special && ratio==0 && counters.loss>=config.lossCount) continue;
                if (!special && ratio>0 && counters.normal>=config.normalCount) continue;

                String member = codec.encode(round);
                GeneratedRound decoded = codec.decode(member);
                RoundResult decodedResult = RoundVerifier.verify(decoded);
                if (!result.mode.equals(decodedResult.mode) || ratio != decodedResult.redisRatio(decoded))
                    throw new IllegalStateException("完整 Round Codec 往返后的模式或实际倍率不一致");

                pending.add(new Member(special, ratio, member));
                if(special)counters.special++; else if(ratio==0)counters.loss++; else counters.normal++;
                counters.accept(special, ratio, result.freeStepCount);
                if (pending.size() >= config.batchSize) flush(redis, pending, config, counters);
            }
            flush(redis, pending, config, counters);
        }
        System.out.println("lossMembers="+counters.loss);
        return counters.summary(config.redisGameId);
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
