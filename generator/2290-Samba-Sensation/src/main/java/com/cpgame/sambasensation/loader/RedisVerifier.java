package com.cpgame.sambasensation.loader;

import com.cpgame.sambasensation.core.GameRuleCore;
import com.cpgame.sambasensation.core.MinimalRoundFactCodec;
import com.cpgame.sambasensation.core.ResultUtil;
import com.cpgame.sambasensation.generator.GeneratorConfig;
import com.cpgame.sambasensation.redis.RedisConnection;
import com.cpgame.sambasensation.redis.RedisKeyContract;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;

/** 从 Redis 重取并独立反推每条 member；不信任 Key 上的倍率。 */
public final class RedisVerifier {
    private RedisVerifier() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("用法: RedisVerifier generator.properties");
        GeneratorConfig config = GeneratorConfig.load(Path.of(args[0]).toAbsolutePath().normalize());
        MinimalRoundFactCodec codec = new MinimalRoundFactCodec();
        EnumMap<GameRuleCore.RoundClass, Integer> classes = new EnumMap<>(GameRuleCore.RoundClass.class);
        EnumMap<GameRuleCore.EntryKind, Integer> entries = new EnumMap<>(GameRuleCore.EntryKind.class);
        int members = 0;
        try (RedisConnection redis = RedisConnection.connect(config)) {
            for (int floor = 1; floor <= 3; floor++) members += verifyIndex(redis, codec, config, false, floor, classes, entries);
            members += verifyIndex(redis, codec, config, true, 1, classes, entries);
        }
        for (GameRuleCore.RoundClass kind : GameRuleCore.RoundClass.values()) if (classes.getOrDefault(kind, 0) < 1) throw new IllegalStateException("Redis缺少可取member: " + kind);
        if (entries.getOrDefault(GameRuleCore.EntryKind.FEATURE_BUY_INITIAL, 0) < 1) throw new IllegalStateException("特殊/mali池缺少可还原的购买入口member");
        System.out.printf("REDIS_VERIFY_PASS members=%d classes=%s entries=%s sourceGameId=%d redisGameId=%d rulesHash=%s%n", members, classes, entries, GameRuleCore.GAME_ID, config.redisGameId, GameRuleCore.RULES_HASH);
    }

    @SuppressWarnings("unchecked")
    private static int verifyIndex(RedisConnection redis, MinimalRoundFactCodec codec, GeneratorConfig config,
                                   boolean special, int betType, EnumMap<GameRuleCore.RoundClass, Integer> classes,
                                   EnumMap<GameRuleCore.EntryKind, Integer> entries) throws Exception {
        String index = special ? RedisKeyContract.specialIndex(config.redisGameId) : RedisKeyContract.normalIndex(config.redisGameId, betType);
        Object response = redis.command("ZRANGE", index, "0", "-1");
        if (!(response instanceof List<?> rawRatios)) throw new IllegalStateException("index is not a ZSET array: " + index);
        int count = 0;
        for (Object rawRatio : rawRatios) {
            int ratio = Integer.parseInt(String.valueOf(rawRatio));
            String list = special ? RedisKeyContract.specialList(config.redisGameId, ratio) : RedisKeyContract.normalList(config.redisGameId, ratio, betType);
            Object values = redis.command("LRANGE", list, "0", "-1");
            if (!(values instanceof List<?> members)) throw new IllegalStateException("list read failed: " + list);
            if (members.size() > config.maxMembersPerMultiplier) throw new IllegalStateException("LTRIM capacity exceeded: " + list);
            for (Object item : members) {
                String member = String.valueOf(item);
                if (!StandardCharsets.US_ASCII.newEncoder().canEncode(member) || member.startsWith("{") || member.startsWith("[")) throw new IllegalStateException("member is not minimal ASCII");
                // 旧SS1结果保留但不得参与当前试玩；本验证只统计当前SS2跨局事实，不删除既有缓存。
                if (!codec.supports(member)) continue;
                if (!special && codec.decode(member).betType() != betType) throw new IllegalStateException("floor key mismatch: " + list);
                ResultUtil.Evaluation result = codec.verify(member);
                if (result.multiplier() != ratio) throw new IllegalStateException("Key multiplier differs from ResultUtil: " + list);
                boolean actualSpecial = switch (result.roundClass()) {
                    case FREE_SPINS_SPECIAL, COIN_COLLECTION_REWARD -> true;
                    case ORDINARY_LOSS, ORDINARY_WIN -> false;
                };
                if (actualSpecial != special) throw new IllegalStateException("pool class mismatch: " + list);
                if (!special && ratio == 0 && result.roundClass() != GameRuleCore.RoundClass.ORDINARY_LOSS) throw new IllegalStateException("0 multiplier is not ordinary loss");
                if (!special && ratio > 0 && result.roundClass() != GameRuleCore.RoundClass.ORDINARY_WIN) throw new IllegalStateException("positive normal member is not ordinary win");
                classes.merge(result.roundClass(), 1, Integer::sum);
                entries.merge(codec.decode(member).entryKind(), 1, Integer::sum);
                count++;
            }
        }
        return count;
    }
}
