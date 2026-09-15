package com.hd.cpgame.magicscroll2.loader;

import com.hd.cpgame.magicscroll2.core.GameRuleCore;
import com.hd.cpgame.magicscroll2.core.GeneratedRound;
import com.hd.cpgame.magicscroll2.core.RedisMemberCodec;
import com.hd.cpgame.magicscroll2.core.ResultUtil;
import com.hd.cpgame.magicscroll2.core.RoundMode;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pre-generates complete Rounds into Redis. Controller runtime has no generation path. */
public final class RedisLoader {
    public LoadSummary run(File configFile) throws Exception {
        GeneratorConfig config = GeneratorConfig.load(configFile);
        GameRuleCore core = new GameRuleCore(config.generationPolicy, config.modeWeights, config.symbolWeights);
        RedisMemberCodec codec = new RedisMemberCodec(core.resultUtil(), config.generationPolicy);
        List<Member> pending = new ArrayList<Member>(config.batchSize);
        Counters counters = new Counters();
        try (RedisConnection redis = RedisConnection.connect(config)) {
            clearExistingGameKeys(redis, config.redisGameId);
            for (int i = 0; i < config.outputLimits.lossTarget(config.lossCount); i++) {
                if (!accept(core.generateCompleteRound(config.paidBet, RoundMode.LOSS), RoundMode.LOSS,
                        false, config, core, codec, pending, redis, counters)) i--;
            }
            for (int i = 0; i < config.winCount; i++) {
                if (!accept(core.generateCompleteRound(config.paidBet, RoundMode.BASE_WIN), RoundMode.BASE_WIN,
                        false, config, core, codec, pending, redis, counters)) i--;
            }
            for (int i = 0; i < config.specialCount; i++) {
                RoundMode mode = (i % 2 == 0) ? RoundMode.XSPLIT : RoundMode.XBOMB_WILD;
                if (!accept(core.generateCompleteRound(config.paidBet, mode), mode,
                        true, config, core, codec, pending, redis, counters)) i--;
            }
            flush(redis, pending, config, counters);
        }
        return counters.summary(config.redisGameId);
    }

    private static void clearExistingGameKeys(RedisConnection redis, long gameId) throws Exception {
        List<String> keys = new ArrayList<String>();
        keys.add(normalIndex(gameId));
        keys.add(specialIndex(gameId));
        for (String pattern : List.of(
                String.format(Locale.ROOT, "BetLog:0%08d:*", gameId),
                String.format(Locale.ROOT, "MaryLog:%09d:*", gameId))) {
            Object found = redis.command("KEYS", pattern);
            if (found instanceof List<?>) {
                for (Object key : (List<?>) found) keys.add(key.toString());
            }
        }
        if (!keys.isEmpty()) {
            List<String> del = new ArrayList<String>(keys.size() + 1);
            del.add("DEL");
            del.addAll(keys);
            redis.command(del.toArray(new String[0]));
        }
    }

    private static boolean accept(GeneratedRound round, RoundMode expected, boolean special, GeneratorConfig config,
                               GameRuleCore core, RedisMemberCodec codec, List<Member> pending,
                               RedisConnection redis, Counters counters) throws Exception {
        LoaderLimits.checkAttempts(++counters.candidates, (long) config.lossCount + config.winCount + config.specialCount);
        ResultUtil.RoundAnalysis analysis = core.resultUtil().analyzeCompleteRound(round, config.generationPolicy);
        if (analysis.getMode() != expected) throw new IllegalStateException("生成类别与目标池不一致");
        int ratio = core.resultUtil().integerRatio(round, config.generationPolicy);
        if (!config.outputLimits.accepts(special, ratio)) return false;
        if (expected == RoundMode.LOSS) {
            if (ratio != 0 || analysis.getPayout().signum() != 0) {
                throw new IllegalStateException("LOSS 必须写入 0 倍未中奖池");
            }
        } else if (ratio <= 0) {
            throw new IllegalStateException("中奖/特殊完整局不能写入 0 倍");
        }
        java.math.BigDecimal maximum = special ? config.specialMaxWinMultiplier : config.normalMaxWinMultiplier;
        if (analysis.getActualMultiplier().compareTo(maximum) > 0) {
            return false;
        }
        String payload = codec.encode(round);
        GeneratedRound rebuilt = codec.decode(payload, config.paidBet);
        ResultUtil.RoundAnalysis rebuiltAnalysis = core.resultUtil()
                .analyzeCompleteRound(rebuilt, config.generationPolicy);
        if (rebuiltAnalysis.getMode() != analysis.getMode()
                || rebuiltAnalysis.getPayout().compareTo(analysis.getPayout()) != 0) {
            throw new IllegalStateException("Redis member 往返后模式或赔付不一致");
        }
        pending.add(new Member(special, ratio, payload));
        counters.accept(expected, ratio, analysis.getDeliveryCount());
        if (pending.size() >= config.batchSize) flush(redis, pending, config, counters);
        return true;
    }

    private static void flush(RedisConnection redis, List<Member> pending,
                              GeneratorConfig config, Counters counters) throws Exception {
        if (pending.isEmpty()) return;
        List<String[]> commands = new ArrayList<String[]>(pending.size() * 3);
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
        counters.loaded += pending.size();
        System.out.println("BATCH_COMMITTED batch=" + counters.batches + " members=" + pending.size()
                + " loaded=" + counters.loaded);
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
        int loss, win, special, batches, loaded, maxDeliveries;
        long candidates;
        final Map<Integer, Integer> normalDistribution = new LinkedHashMap<Integer, Integer>();
        final Map<Integer, Integer> specialDistribution = new LinkedHashMap<Integer, Integer>();
        void accept(RoundMode mode, int multiplier, int deliveries) {
            if (mode == RoundMode.LOSS) loss++;
            else if (mode == RoundMode.BASE_WIN) win++;
            else special++;
            Map<Integer, Integer> distribution = (mode == RoundMode.XSPLIT || mode == RoundMode.XBOMB_WILD)
                    ? specialDistribution : normalDistribution;
            Integer count = distribution.get(multiplier);
            distribution.put(multiplier, count == null ? 1 : count + 1);
            maxDeliveries = Math.max(maxDeliveries, deliveries);
        }
        LoadSummary summary(long gameId) {
            return new LoadSummary(gameId, loss, win, special, batches, candidates, maxDeliveries,
                    new LinkedHashMap<Integer, Integer>(normalDistribution),
                    new LinkedHashMap<Integer, Integer>(specialDistribution));
        }
    }

    public static final class LoadSummary {
        public final long redisGameId;
        public final int lossMembers, winMembers, specialMembers, batches, maxDeliveries;
        public final long candidates;
        public final Map<Integer, Integer> normalDistribution, specialDistribution;
        LoadSummary(long redisGameId, int lossMembers, int winMembers, int specialMembers, int batches,
                    long candidates, int maxDeliveries,
                    Map<Integer, Integer> normalDistribution, Map<Integer, Integer> specialDistribution) {
            this.redisGameId = redisGameId;
            this.lossMembers = lossMembers;
            this.winMembers = winMembers;
            this.specialMembers = specialMembers;
            this.batches = batches;
            this.candidates = candidates;
            this.maxDeliveries = maxDeliveries;
            this.normalDistribution = normalDistribution;
            this.specialDistribution = specialDistribution;
        }
    }
}
