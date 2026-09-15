package com.cpgame.monsterslayer.loader;

import com.cpgame.monsterslayer.core.GameRuleCore;
import com.cpgame.monsterslayer.core.MinimalRoundFactCodec;
import com.cpgame.monsterslayer.core.ResultUtil;
import com.cpgame.monsterslayer.generator.CompleteRoundFactory;
import com.cpgame.monsterslayer.generator.GeneratorConfig;
import com.cpgame.monsterslayer.redis.RedisConnection;
import com.cpgame.monsterslayer.redis.RedisKeyContract;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** Generate, verify and commit at most one batch of complete rounds at a time. */
public final class RedisLoader {
    private RedisLoader() {}
    public static void main(String[] args) {
        try {
            if (args.length > 1) throw new IllegalArgumentException("usage: java -jar monster-slayer-redis-loader.jar [generator.properties]");
            Path config;
            if (args.length == 1) config = Path.of(args[0]).toAbsolutePath();
            else {
                Path location = Path.of(RedisLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                config = location.getParent().resolve("generator.properties");
            }
            Summary s = run(config);
            System.out.println(completionLine(s));
            System.out.printf("LOADER_OK loss=%d win=%d buy3=%d buy4=%d buy5=%d rulesHash=%s%n",
                    s.loss(), s.win(), s.buy3(), s.buy4(), s.buy5(), GameRuleCore.RULES_HASH);
        } catch (Exception e) {
            System.err.println("[FAILED] " + e.getMessage());
            System.exit(1);
        }
    }
    public static Summary run(Path configPath) throws Exception {
        GeneratorConfig c = GeneratorConfig.load(configPath);
        CompleteRoundFactory factory = new CompleteRoundFactory(c.normalWeights, c.maryWeights);
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec();
        SecureRandom random = new SecureRandom();
        Progress progress = new Progress();
        int lossTarget=c.outputLimits.lossTarget(c.lossCount);
        long total = (long) lossTarget + c.winCount + 3L * c.buyCountPerMode;
        System.out.printf("LOAD_START target=%d batchSize=%d loss=%d win=%d naturalSpecial=%d buyPerMode=%d config=%s%n",
                total, c.batchSize, c.lossCount, c.winCount, c.specialCount, c.buyCountPerMode,
                configPath.toAbsolutePath());
        try (RedisConnection redis = RedisConnection.connect(c)) {
            int[] remaining = {lossTarget, c.winCount, c.buyCountPerMode, c.buyCountPerMode, c.buyCountPerMode};
            // Losses and wins share pool 0; each purchase mode has its own pool.
            boolean[] prepared = new boolean[4];
            while (progress.loaded() < total) {
                for (int phase = 0; phase < remaining.length; phase++) {
                    if (remaining[phase] == 0) continue;
                    int buyType = phase < 2 ? 0 : phase + 1;
                    int pool = phase < 2 ? 0 : phase - 1;
                    int size = Math.min(c.batchSize, remaining[phase]);
                    List<Member> batch = generateBatch(factory, codec, random, c, phase, size);
                    if (!prepared[pool]) {
                        preparePool(redis, c, buyType);
                        prepared[pool] = true;
                    }
                    commitBatch(redis, batch, c);
                    remaining[phase] -= batch.size();
                    progress.record(batch, phase);
                    System.out.printf("BATCH_COMMITTED batch=%d members=%d loaded=%d normal=%d special=%d phase=%s%n",
                            progress.batches, batch.size(), progress.loaded(), progress.normal, progress.special,
                            phase == 0 ? "loss" : phase == 1 ? "win" : "buy" + buyType);
                }
            }
        }
        return new Summary(lossTarget, c.winCount, c.buyCountPerMode, c.buyCountPerMode, c.buyCountPerMode);
    }

    static String completionLine(Summary s) {
        long normal = (long) s.loss() + s.win();
        long special = (long) s.buy3() + s.buy4() + s.buy5();
        return "LOAD_COMPLETE loaded=" + (normal + special) + " normal=" + normal + " special=" + special;
    }

    private static List<Member> generateBatch(CompleteRoundFactory factory, MinimalRoundFactCodec codec,
            SecureRandom random, GeneratorConfig c, int phase, int size) {
        List<Member> batch = new ArrayList<>(size);
        int buyType = phase < 2 ? 0 : phase + 1;
        long attempts = 0;
        long retryLimit = 1000L * size;
        while (batch.size() < size) {
            if (++attempts > retryLimit) {
                throw new IllegalStateException("phase " + phase + " generation exceeded batch retry cap; "
                        + "check configured weights and multiplier limits (accepted=" + batch.size() + "/" + size + ")");
            }
            GameRuleCore.CompleteRound round = phase == 0 ? factory.generateLoss(random)
                    : phase == 1 ? factory.generateWin(random) : factory.generateBuy(buyType, random);
            int mult = ResultUtil.redisMultiplierCenti(round);
            if (phase == 0 && mult != 0) continue;
            if (phase == 1 && (mult < c.normalMinWinMultiplier || mult > c.normalMaxWinMultiplier)) continue;
            if (buyType != 0 && (mult < c.maryMinWinMultiplier || mult > c.maryMaxWinMultiplier)) continue;
            if (!c.outputLimits.accepts(buyType!=0,mult) || !withinRoundLimits(round,c.maxConsecutiveWins,c.maxMarySpins)) continue;
            if (mult > c.maxCentiMultiplier) continue;
            batch.add(verified(codec, round, c.maxCentiMultiplier, buyType));
        }
        return batch;
    }

    private static boolean withinRoundLimits(GameRuleCore.CompleteRound round,int maxWins,int maxSpins) {
        if(round.special() && round.steps().size()>maxSpins) return false;
        int streak=0;
        for(var step:ResultUtil.evaluate(round).steps()) {
            streak=step.multiplierCenti()>0?streak+1:0;
            if(streak>maxWins)return false;
        }
        return true;
    }

    private static Member verified(MinimalRoundFactCodec codec, GameRuleCore.CompleteRound round, int maxCenti, int buyType) {
        ResultUtil.RoundResult result = ResultUtil.evaluate(round);
        int mult = ResultUtil.redisMultiplierCenti(round);
        if (mult < 0 || mult > maxCenti) throw new IllegalStateException("generated multiplier exceeds configured cap: " + mult);
        if (buyType == 0 && result.roundClass() == GameRuleCore.RoundClass.BUY_FEATURE)
            throw new IllegalStateException("ordinary generator emitted buy");
        if (buyType != 0 && result.roundClass() != GameRuleCore.RoundClass.BUY_FEATURE)
            throw new IllegalStateException("buy generator emitted non-buy");
        String member = codec.encode(round);
        GameRuleCore.CompleteRound decoded = codec.decode(member);
        if (ResultUtil.redisMultiplierCenti(decoded) != mult) throw new IllegalStateException("codec verification mismatch");
        return new Member(buyType, mult, member);
    }

    /** Clear only the requested pool, once, after its first batch has passed validation. */
    private static void preparePool(RedisConnection redis, GeneratorConfig c, int buyType) throws Exception {
        List<String> indexes = buyType == 0 ? List.of(RedisKeyContract.normalIndex(c.redisGameId))
                : RedisKeyContract.buyIndexesToDelete(c.redisGameId, buyType);
        for (String index : indexes) {
            for (long start = 0; ; start += c.batchSize) {
                Object raw = redis.command("ZRANGE", index, Long.toString(start), Long.toString(start + c.batchSize - 1));
                if (!(raw instanceof List<?> buckets) || buckets.isEmpty()) break;
                List<String[]> commands = new ArrayList<>(buckets.size());
                for (Object bucket : buckets) {
                    int mult = Integer.parseInt(bucket.toString());
                    String key = buyType == 0 ? RedisKeyContract.normalList(c.redisGameId, mult)
                            : RedisKeyContract.buyList(c.redisGameId, buyType, mult);
                    commands.add(new String[]{"DEL", key});
                }
                redis.transaction(commands);
                if (buckets.size() < c.batchSize) break;
            }
            redis.command("DEL", index);
        }
    }

    private static void commitBatch(RedisConnection redis, List<Member> members, GeneratorConfig c) throws Exception {
        List<String[]> commands = new ArrayList<>(members.size() * 4);
        for (Member member : members) {
            int buyType = member.buyType;
            String multiplier = Integer.toString(member.mult);
            String key = buyType == 0 ? RedisKeyContract.normalList(c.redisGameId, member.mult)
                    : RedisKeyContract.buyList(c.redisGameId, buyType, member.mult);
            List<String> indexes = buyType == 0 ? List.of(RedisKeyContract.normalIndex(c.redisGameId))
                    : RedisKeyContract.buyIndexesToWrite(c.redisGameId, buyType);
            for (String index : indexes) commands.add(new String[]{"ZADD", index, multiplier, multiplier});
            commands.add(new String[]{"RPUSH", key, member.value});
            int limit = buyType == 0 ? c.maxMembersPerMultiplier : c.outputLimits.specialCap;
            commands.add(new String[]{"LTRIM", key, "-" + limit, "-1"});
        }
        // One transaction commits complete rounds; never split a round across transactions.
        redis.transaction(commands);
    }

    private static final class Progress {
        long batches, normal, special;
        void record(List<Member> members, int phase) {
            batches++;
            if (phase < 2) normal += members.size(); else special += members.size();
        }
        long loaded() { return normal + special; }
    }

    private record Member(int buyType, int mult, String value) {}
    public record Summary(int loss, int win, int buy3, int buy4, int buy5) {}
}
