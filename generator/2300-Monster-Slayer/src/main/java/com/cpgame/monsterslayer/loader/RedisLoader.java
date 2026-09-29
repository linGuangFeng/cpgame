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
            // Do not wipe existing Redis keys on start; RPUSH+LTRIM caps each bucket.
            while (progress.loaded() < total) {
                boolean any = false;
                for (int phase = 0; phase < remaining.length; phase++) {
                    if (remaining[phase] == 0) continue;
                    any = true;
                    int buyType = phase < 2 ? 0 : phase + 1;
                    int size = Math.min(c.batchSize, remaining[phase]);
                    List<Member> batch = generateBatch(factory, codec, random, c, phase, size);
                    if (batch.isEmpty()) {
                        remaining[phase] = 0;
                        continue;
                    }
                    try {
                        commitBatch(redis, batch, c);
                    } catch (Exception redisError) {
                        if (progress.batches > 0 && redisProgressStop(redisError)) {
                            System.out.println("[warn] Redis stopped after " + progress.batches
                                    + " batches: " + redisError.getMessage());
                            remaining = new int[remaining.length];
                            break;
                        }
                        throw redisError;
                    }
                    remaining[phase] -= batch.size();
                    if (batch.size() < size) remaining[phase] = 0;
                    progress.record(batch, phase);
                    System.out.printf("BATCH_COMMITTED batch=%d members=%d loaded=%d normal=%d special=%d phase=%s%n",
                            progress.batches, batch.size(), progress.loaded(), progress.normal, progress.special,
                            phase == 0 ? "loss" : phase == 1 ? "win" : "buy" + buyType);
                }
                if (!any) break;
            }
        }
        return new Summary(lossTarget, c.winCount, c.buyCountPerMode, c.buyCountPerMode, c.buyCountPerMode);
    }

    static boolean redisProgressStop(Throwable error) {
        String text = error == null ? "" : String.valueOf(error.getMessage());
        if (error != null && error.getCause() != null) text += " " + error.getCause().getMessage();
        return text.contains("OOM") || text.contains("maxmemory") || text.contains("timed out")
                || text.contains("Timed out") || text.contains("MISCONF") || text.contains("Connection reset")
                || text.contains("closed") || text.contains("EXECABORT") || text.contains("Broken pipe")
                || text.contains("已关闭连接") || text.contains("中止了一个已建立");
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
                System.out.println("[warn] phase " + phase + " stopping with " + batch.size() + "/" + size
                        + " after candidate limit");
                break;
            }
            GameRuleCore.CompleteRound round = phase == 0 ? factory.generateLoss(random)
                    : phase == 1 ? factory.generateWin(random) : factory.generateBuy(buyType, random);
            int mult = ResultUtil.redisMultiplierCenti(round);
            if (phase == 0 && mult != 0) continue;
            if (phase == 1 && (mult < c.normalMinWinMultiplier || mult > c.normalMaxWinMultiplier)) continue;
            if (buyType != 0 && (mult < c.maryMinWinMultiplier || mult > c.maryMaxWinMultiplier)) continue;
            if (!c.outputLimits.accepts(buyType!=0,mult) || !withinRoundLimits(round,c.maxConsecutiveWins,c.maxMarySpins)) continue;
            if (mult < 0 || mult > c.maxCentiMultiplier) continue;
            GameRuleCore.RoundClass roundClass = ResultUtil.evaluate(round).roundClass();
            if (buyType == 0 && roundClass == GameRuleCore.RoundClass.BUY_FEATURE) continue;
            if (buyType != 0 && roundClass != GameRuleCore.RoundClass.BUY_FEATURE) continue;
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
        byte[] member = codec.encode(round);
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
                    int mult = Integer.parseInt(bucket instanceof byte[] bb ? new String(bb, java.nio.charset.StandardCharsets.US_ASCII) : bucket.toString());
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
        List<byte[][]> commands = new ArrayList<>(members.size() * 4);
        for (Member member : members) {
            int buyType = member.buyType;
            String multiplier = Integer.toString(member.mult);
            String key = buyType == 0 ? RedisKeyContract.normalList(c.redisGameId, member.mult)
                    : RedisKeyContract.buyList(c.redisGameId, buyType, member.mult);
            List<String> indexes = buyType == 0 ? List.of(RedisKeyContract.normalIndex(c.redisGameId))
                    : RedisKeyContract.buyIndexesToWrite(c.redisGameId, buyType);
            for (String index : indexes)
                commands.add(bytes("ZADD", index, multiplier, multiplier));
            commands.add(new byte[][]{
                    "RPUSH".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    key.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    member.value});
            int limit = buyType == 0 ? c.maxMembersPerMultiplier : c.outputLimits.specialCap;
            commands.add(bytes("LTRIM", key, "-" + limit, "-1"));
        }
        // One transaction commits complete rounds; never split a round across transactions.
        redis.transactionBinary(commands);
    }

    private static byte[][] bytes(String... parts) {
        byte[][] out = new byte[parts.length][];
        for (int i = 0; i < parts.length; i++) out[i] = parts[i].getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return out;
    }

    private static final class Progress {
        long batches, normal, special;
        void record(List<Member> members, int phase) {
            batches++;
            if (phase < 2) normal += members.size(); else special += members.size();
        }
        long loaded() { return normal + special; }
    }

    private record Member(int buyType, int mult, byte[] value) {}
    public record Summary(int loss, int win, int buy3, int buy4, int buy5) {}
}
